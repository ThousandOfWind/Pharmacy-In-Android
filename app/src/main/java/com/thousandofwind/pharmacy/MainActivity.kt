package com.thousandofwind.pharmacy

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import coil.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "medicines")
data class MedicineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "expiry_date") val expiryDate: String,
    val efficacy: String,
    val notes: String,
    @ColumnInfo(name = "remaining_quantity") val remainingQuantity: Int?,
    @ColumnInfo(name = "photo_uri") val photoUri: String,
    @ColumnInfo(name = "ocr_raw_text") val ocrRawText: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Dao
interface MedicineDao {
    @Query(
        """
        SELECT * FROM medicines
        WHERE (:query = '' OR name LIKE '%' || :query || '%' OR efficacy LIKE '%' || :query || '%' OR notes LIKE '%' || :query || '%')
        ORDER BY created_at DESC
        """
    )
    fun observeByQuery(query: String): Flow<List<MedicineEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MedicineEntity)
}

@Database(entities = [MedicineEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun medicineDao(): MedicineDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pharmacy.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}

data class MedicineForm(
    val editingId: Long? = null,
    val name: String = "",
    val expiryDate: String = "",
    val efficacy: String = "",
    val notes: String = "",
    val remainingQuantityText: String = "",
    val photoUri: String = "",
    val ocrRawText: String = ""
)

data class UiState(
    val search: String = "",
    val medicines: List<MedicineEntity> = emptyList(),
    val form: MedicineForm = MedicineForm(),
    val isSaving: Boolean = false,
    val isScanning: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).medicineDao()
    private val searchFlow = MutableStateFlow("")
    private val formFlow = MutableStateFlow(MedicineForm())
    private val savingFlow = MutableStateFlow(false)
    private val scanningFlow = MutableStateFlow(false)

    val uiState: StateFlow<UiState> = combine(
        searchFlow,
        formFlow,
        savingFlow,
        scanningFlow,
        searchFlow.flatMapLatest { dao.observeByQuery(it.trim()) }
    ) { search, form, saving, scanning, medicines ->
        UiState(search, medicines, form, saving, scanning)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())

    fun updateSearch(value: String) {
        searchFlow.value = value
    }

    fun startNewForm() {
        formFlow.value = MedicineForm()
    }

    fun editMedicine(item: MedicineEntity) {
        formFlow.value = MedicineForm(
            editingId = item.id,
            name = item.name,
            expiryDate = item.expiryDate,
            efficacy = item.efficacy,
            notes = item.notes,
            remainingQuantityText = item.remainingQuantity?.toString().orEmpty(),
            photoUri = item.photoUri,
            ocrRawText = item.ocrRawText
        )
    }

    fun updateForm(update: MedicineForm.() -> MedicineForm) {
        formFlow.value = formFlow.value.update()
    }

    fun saveCurrentForm(): Boolean {
        val form = formFlow.value
        if (form.name.isBlank()) return false

        val remaining = form.remainingQuantityText.trim().toIntOrNull()
        viewModelScope.launch(Dispatchers.IO) {
            savingFlow.value = true
            dao.upsert(
                MedicineEntity(
                    id = form.editingId ?: 0,
                    name = form.name.trim(),
                    expiryDate = form.expiryDate.trim(),
                    efficacy = form.efficacy.trim(),
                    notes = form.notes.trim(),
                    remainingQuantity = remaining,
                    photoUri = form.photoUri,
                    ocrRawText = form.ocrRawText,
                    createdAt = System.currentTimeMillis()
                )
            )
            formFlow.value = MedicineForm()
            savingFlow.value = false
        }
        return true
    }

    fun fillFormByOcrResult(rawText: String) {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val expiry = lines.firstOrNull { it.contains("有效期") || it.contains("保质期") }
            ?: Regex("20\\d{2}[./-]\\d{1,2}[./-]\\d{1,2}").find(rawText)?.value.orEmpty()
        val efficacy = lines.firstOrNull { it.contains("适应症") || it.contains("功能主治") || it.contains("功效") }.orEmpty()
        val name = lines.firstOrNull().orEmpty()

        formFlow.value = formFlow.value.copy(
            name = if (formFlow.value.name.isBlank()) name else formFlow.value.name,
            expiryDate = if (formFlow.value.expiryDate.isBlank()) expiry else formFlow.value.expiryDate,
            efficacy = if (formFlow.value.efficacy.isBlank()) efficacy else formFlow.value.efficacy,
            ocrRawText = rawText
        )
    }

    fun scanPhoto(uri: Uri, onError: (String) -> Unit) {
        val context = getApplication<Application>()
        scanningFlow.value = true
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val image = InputImage.fromFilePath(context, uri)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                scanningFlow.value = false
                fillFormByOcrResult(result.text)
            }
            .addOnFailureListener {
                scanningFlow.value = false
                onError("文字识别失败：${it.localizedMessage ?: "未知错误"}")
            }
    }
}

class MainViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel> { MainViewModelFactory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MedicineApp(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicineApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var cameraUri by rememberSaveable { mutableStateOf("") }

    val requestCameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) scope.launch { snackbars.showSnackbar("需要相机权限才能拍照") }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraUri.isNotBlank()) {
            viewModel.updateForm { copy(photoUri = cameraUri) }
            viewModel.scanPhoto(Uri.parse(cameraUri)) { msg ->
                scope.launch { snackbars.showSnackbar(msg) }
            }
        }
    }

    fun takePhoto() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        val uri = createImageUri(context)
        cameraUri = uri.toString()
        cameraLauncher.launch(uri)
    }

    val totalRemaining = uiState.medicines.mapNotNull { it.remainingQuantity }.sum()
    val lowStockCount = uiState.medicines.count { (it.remainingQuantity ?: Int.MAX_VALUE) <= 3 }

    Scaffold(
        topBar = { TopAppBar(title = { Text("药品本地管理") }) },
        snackbarHost = { SnackbarHost(snackbars) },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::startNewForm) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = uiState.search,
                onValueChange = viewModel::updateSearch,
                label = { Text("搜索药品名称/功效/备注") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("药品数: ${uiState.medicines.size}")
                    Text("余量总计: $totalRemaining")
                    Text("低余量(<=3): $lowStockCount")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            MedicineFormCard(
                form = uiState.form,
                scanning = uiState.isScanning,
                saving = uiState.isSaving,
                onUpdate = { updater -> viewModel.updateForm(updater) },
                onTakePhoto = ::takePhoto,
                onSave = {
                    val ok = viewModel.saveCurrentForm()
                    scope.launch {
                        snackbars.showSnackbar(if (ok) "药品已保存" else "请先填写药品名称")
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "已保存药品", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(uiState.medicines, key = { it.id }) { item ->
                    MedicineItem(item = item, onEdit = { viewModel.editMedicine(item) })
                }
            }
        }
    }
}

@Composable
private fun MedicineFormCard(
    form: MedicineForm,
    scanning: Boolean,
    saving: Boolean,
    onUpdate: ((MedicineForm.() -> MedicineForm)) -> Unit,
    onTakePhoto: () -> Unit,
    onSave: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (form.photoUri.isNotBlank()) {
                AsyncImage(
                    model = form.photoUri,
                    contentDescription = "药品照片",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentScale = ContentScale.Crop
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTakePhoto) { Text("拍照识别") }
                Text(
                    text = if (scanning) "正在识别文字..." else "名称必填，其他可选，可手工修改",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            OutlinedTextField(
                value = form.name,
                onValueChange = { onUpdate { copy(name = it) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("药品名称（必填）") }
            )

            OutlinedTextField(
                value = form.expiryDate,
                onValueChange = { onUpdate { copy(expiryDate = it) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("保质期/有效期（可选）") }
            )

            OutlinedTextField(
                value = form.efficacy,
                onValueChange = { onUpdate { copy(efficacy = it) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("主要功效（可选）") }
            )

            OutlinedTextField(
                value = form.remainingQuantityText,
                onValueChange = { onUpdate { copy(remainingQuantityText = it.filter(Char::isDigit)) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("余量（可选）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = form.notes,
                onValueChange = { onUpdate { copy(notes = it) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("备注（可选）") }
            )

            Button(onClick = onSave, enabled = !saving) {
                Text(if (saving) "保存中..." else if (form.editingId == null) "新增到本地" else "更新到本地")
            }
        }
    }
}

@Composable
private fun MedicineItem(item: MedicineEntity, onEdit: () -> Unit) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (item.photoUri.isNotBlank()) {
                AsyncImage(
                    model = item.photoUri,
                    contentDescription = item.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentScale = ContentScale.Crop
                )
            }
            Text(text = item.name, style = MaterialTheme.typography.titleMedium)
            if (item.expiryDate.isNotBlank()) Text(text = "保质期: ${item.expiryDate}")
            if (item.efficacy.isNotBlank()) Text(text = "功效: ${item.efficacy}")
            if (item.remainingQuantity != null) Text(text = "余量: ${item.remainingQuantity}")
            if (item.notes.isNotBlank()) Text(text = "备注: ${item.notes}")

            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "更新时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp)
                )
                TextButton(onClick = onEdit) { Text("编辑") }
            }
        }
    }
}

private fun createImageUri(context: Context): Uri {
    val imagesDir = File(context.cacheDir, "images").apply { mkdirs() }
    val file = File(imagesDir, "medicine_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
}
