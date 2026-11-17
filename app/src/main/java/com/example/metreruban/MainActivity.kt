package com.example.metreruban

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.ar.core.Config
import com.google.ar.core.Frame
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.arcore.isValid
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.node.SphereNode
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberView
import kotlin.math.sqrt

private val Jaune = Color(0xFFFFC61A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Jaune)) { Racine() }
        }
    }
}

@Composable
fun Racine() {
    val ctx = LocalContext.current
    var ok by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val demande = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok = it }
    LaunchedEffect(Unit) { if (!ok) demande.launch(Manifest.permission.CAMERA) }

    if (ok) EcranMesure() else Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text("Autorisez la caméra pour mesurer.")
    }
}

@Composable
fun EcranMesure() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberARCameraNode(engine)
    val childNodes = rememberNodes()
    val view = rememberView(engine)
    val collisionSystem = rememberCollisionSystem(view)

    var frame by remember { mutableStateOf<Frame?>(null) }
    val points = remember { mutableStateListOf<FloatArray>() } // positions en mètres

    fun d(a: FloatArray, b: FloatArray): Float {
        val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]
        return sqrt(x * x + y * y + z * z)
    }
    val dernier = if (points.size >= 2) d(points[points.size - 2], points.last()) else null
    val total = points.zipWithNext().sumOf { (a, b) -> d(a, b).toDouble() }.toFloat()

    Box(Modifier.fillMaxSize()) {
        ARScene(
            modifier = Modifier.fillMaxSize(),
            childNodes = childNodes,
            engine = engine,
            view = view,
            modelLoader = modelLoader,
            collisionSystem = collisionSystem,
            cameraNode = cameraNode,
            planeRenderer = true,
            sessionConfiguration = { session, config ->
                config.depthMode =
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                        Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                config.lightEstimationMode = Config.LightEstimationMode.DISABLED
            },
            onSessionUpdated = { _, f -> frame = f },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { event, _ ->
                    frame?.hitTest(event.x, event.y)
                        ?.firstOrNull { it.isValid(depthPoint = true, point = true) }
                        ?.createAnchor()
                        ?.let { anchor ->
                            val repere = AnchorNode(engine = engine, anchor = anchor).apply {
                                addChildNode(
                                    SphereNode(
                                        engine = engine,
                                        radius = 0.008f,
                                        materialInstance = materialLoader.createColorInstance(Jaune)
                                    )
                                )
                            }
                            childNodes += repere
                            points += anchor.pose.translation
                        }
                }
            )
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = Color(0xCC121212)) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(
                        if (dernier != null) "%.1f cm".format(dernier * 100)
                        else "Touchez un point sur une surface",
                        fontSize = 28.sp, color = Jaune
                    )
                    if (points.size >= 3) {
                        Text("Total : %.1f cm".format(total * 100), color = Color.White)
                    } else if (dernier == null) {
                        Text("Bougez le téléphone pour détecter les surfaces, puis touchez deux points.", color = Color.White)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = points.isNotEmpty(),
                    onClick = {
                        (childNodes.lastOrNull() as? AnchorNode)?.anchor?.detach()
                        if (childNodes.isNotEmpty()) childNodes.removeAt(childNodes.lastIndex)
                        if (points.isNotEmpty()) points.removeAt(points.lastIndex)
                    }
                ) { Text("Annuler") }
                OutlinedButton(
                    enabled = points.isNotEmpty(),
                    onClick = {
                        childNodes.filterIsInstance<AnchorNode>().forEach { it.anchor.detach() }
                        childNodes.clear(); points.clear()
                    }
                ) { Text("Tout effacer") }
            }
        }
    }
}
