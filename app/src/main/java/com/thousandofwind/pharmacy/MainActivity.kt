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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF176C62), onPrimary = Color.White,
                primaryContainer = Color(0xFFD7EEE7), onPrimaryContainer = Color(0xFF123E37),
                secondaryContainer = Color(0xFFEAF1ED), background = Color(0xFFF7F9F6),
                surface = Color(0xFFF7F9F6), surfaceVariant = Color(0xFFEAF1ED)
            )) { MedicineApp(viewModel) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicineApp(viewModel: MedicineViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showAdd by rememberSaveable { mutableStateOf(false) }
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
                title = { Text(when (state.session.screen) {
                    Screen.CABINET -> "我的药箱"
                    Screen.EDITOR -> if (state.session.draft.id == 0L) "核对并添加" else "编辑药品"
                    Screen.DETAIL -> "药品详情"
                }) },
                navigationIcon = {
                    if (state.session.screen != Screen.CABINET) TextButton(onClick = ::back, enabled = !state.busy) { Text("返回") }
                },
                actions = {
                    if (state.session.screen == Screen.CABINET) TextButton(
                        onClick = { showAdd = true }, enabled = state.loaded && state.databaseError == null
                    ) { Text("＋ 添加") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.session.screen == Screen.EDITOR) Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    state.session.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(
                        onClick = viewModel::save,
                        enabled = state.loaded && !state.busy && !state.scanning && state.databaseError == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                    ) { Text(if (state.busy) "保存中…" else if (state.session.draft.id == 0L) "保存到药箱" else "保存修改") }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.session.screen) {
                Screen.CABINET -> CabinetScreen(state, viewModel::search, viewModel::filter, viewModel::filterTag, { showAdd = true }, viewModel::open)
                Screen.EDITOR -> EditorScreen(state, viewModel::updateDraft, ::takePhoto, ::pickPhoto)
                Screen.DETAIL -> state.selected?.let { item ->
                    DetailScreen(item, state.busy, { viewModel.edit(item) },
                        { quantity, unit, done -> viewModel.updateStock(item, quantity, unit, done) }, { viewModel.remove(item) })
                } ?: Column(Modifier.padding(24.dp)) {
                    Text(if (!state.loaded) "正在读取…" else state.databaseError ?: "这条记录已不在药箱中")
                    TextButton(onClick = viewModel::back) { Text("回到药箱") }
                }
            }
        }
    }
    if (showAdd) AlertDialog(
        onDismissRequest = { showAdd = false },
        title = { Text("添加到药箱") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("先拍药盒正面，之后可补拍有效期。也可以直接手动填写。")
                Button(onClick = { showAdd = false; viewModel.beginNew(); takePhoto() }, modifier = Modifier.fillMaxWidth()) { Text("拍照添加") }
                OutlinedButton(onClick = { showAdd = false; viewModel.beginNew(); pickPhoto() }, modifier = Modifier.fillMaxWidth()) { Text("从相册选择") }
                TextButton(onClick = { showAdd = false; viewModel.beginNew() }, modifier = Modifier.fillMaxWidth()) { Text("手动添加") }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } }
    )
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("放弃本次修改？") }, text = { Text("还没有保存，返回后本次填写将被丢弃。") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; viewModel.back() }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("继续填写") } }
    )
}
