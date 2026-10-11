package com.thousandofwind.pharmacy

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MedicineViewModel> {
        viewModelFactory { initializer { MedicineViewModel(application, createSavedStateHandle()) } }
    }

    private val updateViewModel by viewModels<AppUpdateViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(252, 250, 243)
        window.navigationBarColor = android.graphics.Color.rgb(252, 250, 243)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            PharmacyTheme { MedicineApp(viewModel, updateViewModel) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicineApp(viewModel: MedicineViewModel, updateViewModel: AppUpdateViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val screenState = rememberSaveableStateHolder()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showUpdate by rememberSaveable { mutableStateOf(false) }
    var showMore by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var cameraUri by rememberSaveable { mutableStateOf("") }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraUri.isNotBlank()) viewModel.scanPhoto(Uri.parse(cameraUri))
        else viewModel.report("未拍摄照片，可以重拍或手动填写")
    }
    fun launchCamera() {
        runCatching {
            cameraUri = PhotoStore.cameraUri(context).toString()
            camera.launch(Uri.parse(cameraUri))
        }.onFailure { viewModel.report("无法打开相机，请从相册选择或手动填写") }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
        else viewModel.report("未获得相机权限，可以从相册选择或手动填写")
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.scanPhoto(uri)
    }
    fun takePhoto() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }
    fun pickPhoto() {
        runCatching {
            picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.onFailure { viewModel.report("无法打开相册，可以拍照或手动填写") }
    }
    fun back() {
        if (viewModel.hasUnsavedChanges()) confirmLeave = true else viewModel.back()
    }
    BackHandler(state.session.screen != Screen.CABINET) { if (!state.busy) back() }
    LaunchedEffect(state.session.screen) {
        if (state.session.screen != Screen.EDITOR) screenState.removeState(Screen.EDITOR)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { notice ->
            val result = snackbar.showSnackbar(notice.text, actionLabel = if (notice.canUndo) "撤销" else null,
                duration = if (notice.canUndo) SnackbarDuration.Long else SnackbarDuration.Short)
            if (notice.canUndo && result == SnackbarResult.ActionPerformed) viewModel.undoRemove()
        }
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    if (state.session.screen == Screen.CABINET) PaperWordmark()
                    else Text(if (state.session.screen == Screen.DETAIL) "药品详情" else if (state.session.draft.id == 0L) "核对并添加" else "编辑药品", style = PaperHeading)
                },
                navigationIcon = {
                    if (state.session.screen != Screen.CABINET) TextButton(onClick = ::back, enabled = !state.busy) { InkIcon(InkSymbol.BACK); Text("返回") }
                },
                actions = {
                    if (state.session.screen == Screen.CABINET) {
                        Box {
                            TextButton(onClick = { showMore = true }) { InkIcon(InkSymbol.MORE); Text("更多") }
                            DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                                DropdownMenuItem(text = { Text("检查更新") }, onClick = { showMore = false; showUpdate = true })
                            }
                        }
                    } else if (state.session.screen == Screen.DETAIL) {
                        state.selected?.let { item -> TextButton(onClick = { viewModel.edit(item) }, enabled = !state.busy) { Text("编辑") } }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.session.screen == Screen.CABINET && state.loaded && state.databaseError == null && state.medicines.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.End) {
                    PenButton("添加药品", { showAdd = true }, icon = InkSymbol.PLUS)
                }
            }
            if (state.session.screen == Screen.EDITOR) Surface {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    state.session.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    PenButton(
                        if (state.busy) "保存中…" else if (state.session.draft.id == 0L) "保存到药箱" else "保存修改",
                        onClick = viewModel::save, enabled = state.loaded && !state.busy && !state.scanning && state.databaseError == null,
                        modifier = Modifier.fillMaxWidth(), icon = InkSymbol.CHECK
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = state, contentKey = { it.session.screen },
                transitionSpec = {
                    val direction = if (targetState.session.screen == Screen.CABINET) -1 else 1
                    (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { direction * it / 30 }) togetherWith
                        (fadeOut(tween(120)) + slideOutHorizontally(tween(180)) { -direction * it / 40 })
                }, label = "page transition"
            ) { page ->
                screenState.SaveableStateProvider(if (page.session.screen == Screen.DETAIL) "detail-${page.session.selectedId}" else page.session.screen) {
                    when (page.session.screen) {
                        Screen.CABINET -> CabinetScreen(page, viewModel::search, viewModel::filter, viewModel::filterTag, { showAdd = true }, viewModel::open)
                        Screen.EDITOR -> EditorScreen(page, viewModel::updateDraft, ::takePhoto, ::pickPhoto)
                        Screen.DETAIL -> page.selected?.let { item ->
                            DetailScreen(item, page.busy, { viewModel.edit(item) },
                                { quantity, unit, done -> viewModel.updateStock(item, quantity, unit, done) }, { viewModel.remove(item) })
                        } ?: Column(Modifier.padding(24.dp)) {
                            Text(if (!page.loaded) "正在读取…" else page.databaseError ?: "这条记录已不在药箱中")
                            TextButton(onClick = viewModel::back) { Text("回到药箱") }
                        }
                    }
                }
            }
        }
    }
    if (showUpdate) UpdateDialog(updateViewModel, onDismiss = { showUpdate = false })
    if (showAdd) ModalBottomSheet(onDismissRequest = { showAdd = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("收好新的一盒", style = PaperHeading)
            Text("拍照帮你记录，由你核对后保存。也可以直接手动填写。", color = PaperPalette.Muted)
            PenButton("拍照添加", { showAdd = false; viewModel.beginNew(); takePhoto() }, Modifier.fillMaxWidth(), icon = InkSymbol.CAMERA)
            TextButton(onClick = { showAdd = false; viewModel.beginNew() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("手动添加", style = MaterialTheme.typography.titleMedium) }
            InkDivider()
            TextButton(onClick = { showAdd = false; viewModel.beginNew(); pickPhoto() }, modifier = Modifier.fillMaxWidth()) { Text("从相册选择") }
        }
    }
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("放弃本次修改？") }, text = { Text("还没有保存，返回后本次填写将被丢弃。") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; viewModel.back() }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("继续填写") } }
    )
}
