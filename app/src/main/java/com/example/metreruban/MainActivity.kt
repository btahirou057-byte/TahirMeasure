package com.example.metreruban

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
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

enum class Mode(val n: Int, val titre: String) {
    DISTANCE(2, "Distance"),
    OUVERTURE(3, "Porte / fenêtre")
}

class Groupe(val mode: Mode) {
    val ancres = mutableStateListOf<Anchor>()
}

private fun dist(a: FloatArray, b: FloatArray): Float {
    val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]
    return sqrt(x * x + y * y + z * z)
}

private fun cm(metres: Float) = "%.1f cm".format(metres * 100)

private fun quatrieme(p1: FloatArray, p2: FloatArray, p3: FloatArray) =
    floatArrayOf(p3[0] + p1[0] - p2[0], p3[1] + p1[1] - p2[1], p3[2] + p1[2] - p2[2])

private fun consigne(m: Mode, k: Int) = when (m) {
    Mode.DISTANCE -> if (k == 0) "Touchez le premier point" else "Touchez le deuxième point"
    Mode.OUVERTURE -> when (k) {
        0 -> "Touchez le coin bas gauche"
        1 -> "Touchez le coin haut gauche"
        else -> "Touchez le coin haut droit"
    }
}

// Convertit un point 3D (mètres) en position sur l'écran
private fun projeter(p: FloatArray, vue: FloatArray, proj: FloatArray, w: Float, h: Float): Offset? {
    val v = floatArrayOf(p[0], p[1], p[2], 1f)
    val e = FloatArray(4)
    val c = FloatArray(4)
    for (r in 0..3) { var s = 0f; for (k in 0..3) s += vue[k * 4 + r] * v[k]; e[r] = s }
    for (r in 0..3) { var s = 0f; for (k in 0..3) s += proj[k * 4 + r] * e[k]; c[r] = s }
    if (c[3] <= 0.0001f) return null
    val x = c[0] / c[3]
    val y = c[1] / c[3]
    return Offset((x * 0.5f + 0.5f) * w, (1f - (y * 0.5f + 0.5f)) * h)
}

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
    var mode by remember { mutableStateOf(Mode.DISTANCE) }
    var tick by remember { mutableIntStateOf(0) }
    val vue = remember { FloatArray(16) }
    val proj = remember { FloatArray(16) }
    val groupes = remember { mutableStateListOf<Groupe>() }
    val textes = rememberTextMeasurer()
    val styleEtiquette = remember {
        TextStyle(color = Jaune, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }

    fun annulerPoint() {
        val g = groupes.lastOrNull() ?: return
        if (g.ancres.isNotEmpty()) {
            g.ancres.removeAt(g.ancres.lastIndex).detach()
            if (childNodes.isNotEmpty()) childNodes.removeAt(childNodes.lastIndex)
        }
        if (g.ancres.isEmpty()) groupes.removeAt(groupes.lastIndex)
    }

    fun changerMode(m: Mode) {
        // Une mesure commencée dans l'ancien mode est abandonnée
        while (groupes.lastOrNull()?.let { it.ancres.size < it.mode.n } == true) annulerPoint()
        mode = m
    }

    fun toutEffacer() {
        groupes.forEach { g -> g.ancres.forEach { it.detach() } }
        groupes.clear()
        childNodes.clear()
    }

    val enCours = groupes.lastOrNull()?.takeIf { it.ancres.size < it.mode.n }
    val fini = groupes.lastOrNull { it.ancres.size == it.mode.n }
    val (titre, sousTitre) = when {
        enCours != null -> consigne(enCours.mode, enCours.ancres.size) to ""
        fini != null -> {
            val p = fini.ancres.map { it.pose.translation }
            if (fini.mode == Mode.DISTANCE) {
                val d = dist(p[0], p[1])
                cm(d) to "%.2f m".format(d)
            } else {
                val h = dist(p[0], p[1])
                val l = dist(p[1], p[2])
                "L ${cm(l)} × H ${cm(h)}" to "Surface : %.2f m²".format(l * h)
            }
        }
        else -> consigne(mode, 0) to "Bougez le téléphone pour détecter les surfaces."
    }

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
            onSessionUpdated = { _, f ->
                frame = f
                if (f.camera.trackingState == TrackingState.TRACKING) {
                    f.camera.getViewMatrix(vue, 0)
                    f.camera.getProjectionMatrix(proj, 0, 0.05f, 100f)
                    tick++
                }
            },
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
                            val dernier = groupes.lastOrNull()
                            val g = if (dernier == null || dernier.ancres.size >= dernier.mode.n)
                                Groupe(mode).also { groupes.add(it) } else dernier
                            g.ancres.add(anchor)
                        }
                }
            )
        )

        // Lignes et longueurs dessinées par-dessus la caméra
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val rafraichir = tick
            val l = size.width
            val h = size.height

            fun ecran(p: FloatArray) = projeter(p, vue, proj, l, h)

            fun etiquette(c: Offset, texte: String) {
                val r = textes.measure(texte, styleEtiquette)
                val w = r.size.width + 24f
                val hh = r.size.height + 12f
                drawRoundRect(
                    color = Color(0xE6121212),
                    topLeft = Offset(c.x - w / 2f, c.y - hh / 2f),
                    size = Size(w, hh),
                    cornerRadius = CornerRadius(hh / 2f)
                )
                drawText(r, topLeft = Offset(c.x - r.size.width / 2f, c.y - r.size.height / 2f))
            }

            fun cote(a: FloatArray, b: FloatArray, avecEtiquette: Boolean = true) {
                val sa = ecran(a)
                val sb = ecran(b)
                if (sa != null && sb != null) {
                    drawLine(color = Jaune, start = sa, end = sb, strokeWidth = 6f)
                    if (avecEtiquette) {
                        etiquette(Offset((sa.x + sb.x) / 2f, (sa.y + sb.y) / 2f), cm(dist(a, b)))
                    }
                }
            }

            for (g in groupes) {
                val p = g.ancres.map { it.pose.translation }
                if (g.mode == Mode.DISTANCE) {
                    if (p.size == 2) cote(p[0], p[1])
                } else {
                    if (p.size >= 2) cote(p[0], p[1])
                    if (p.size == 3) {
                        val p4 = quatrieme(p[0], p[1], p[2])
                        cote(p[1], p[2])
                        cote(p[2], p4, avecEtiquette = false)
                        cote(p4, p[0], avecEtiquette = false)
                        val coins = listOf(p[0], p[1], p[2], p4).map { ecran(it) }
                        if (coins.all { it != null }) {
                            val cx = coins.sumOf { it!!.x.toDouble() }.toFloat() / 4f
                            val cy = coins.sumOf { it!!.y.toDouble() }.toFloat() / 4f
                            val surface = dist(p[0], p[1]) * dist(p[1], p[2])
                            etiquette(Offset(cx, cy), "%.2f m²".format(surface))
                        }
                    }
                }
                p.forEach { q -> ecran(q)?.let { drawCircle(color = Jaune, radius = 10f, center = it) } }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = Color(0xCC121212)) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(titre, fontSize = 22.sp, color = Jaune)
                    if (sousTitre.isNotEmpty()) Text(sousTitre, color = Color.White)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.values().forEach { m ->
                    if (m == mode) Button(onClick = { changerMode(m) }) { Text(m.titre) }
                    else OutlinedButton(onClick = { changerMode(m) }) { Text(m.titre) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = groupes.isNotEmpty(), onClick = { annulerPoint() }) { Text("Annuler") }
                OutlinedButton(enabled = groupes.isNotEmpty(), onClick = { toutEffacer() }) { Text("Tout effacer") }
            }
        }
    }
}
