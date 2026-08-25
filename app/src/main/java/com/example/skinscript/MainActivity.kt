package com.example.skinscript

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.skinscript.data.AssetCategory
import com.example.skinscript.data.InstallSummary
import com.example.skinscript.data.OverwriteMode
import com.example.skinscript.data.SkinPackage
import com.example.skinscript.data.updater.UpdateCheckState
import com.example.skinscript.installer.OverwriteDecision
import com.example.skinscript.shizuku.ShizukuState
import com.example.skinscript.ui.OverwritePromptData
import com.example.skinscript.ui.SkinInstallerViewModel
import com.example.skinscript.ui.marketplace.MarketplaceScreen
import com.example.skinscript.ui.marketplace.MarketplaceViewModel
import com.example.skinscript.ui.theme.SkinScriptTheme
import com.example.skinscript.ui.updater.AppUpdateDialog

enum class MainTab(val title: String, val icon: ImageVector) {
    INSTALLER("Installer", Icons.Default.FolderZip),
    MARKETPLACE("Marketplace", Icons.Default.Storefront)
}

class MainActivity : ComponentActivity() {

    private val viewModel: SkinInstallerViewModel by viewModels()
    private val marketplaceViewModel: MarketplaceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        setContent {
            SkinScriptTheme {
                SkinInstallerApp(
                    viewModel = viewModel,
                    marketplaceViewModel = marketplaceViewModel,
                    onOpenZipPicker = { uri -> viewModel.loadZip(uri) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        val uri: Uri? = when (action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                } ?: intent.clipData?.getItemAt(0)?.uri ?: intent.data
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.clipData?.getItemAt(0)?.uri
            }
            else -> intent.data
        }

        if (uri != null) {
            viewModel.loadZip(uri)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinInstallerApp(
    viewModel: SkinInstallerViewModel,
    marketplaceViewModel: MarketplaceViewModel,
    onOpenZipPicker: (Uri) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(MainTab.INSTALLER) }

    val shizukuState by viewModel.shizukuState.collectAsState()
    val destinationPath by viewModel.destinationPath.collectAsState()
    val overwriteMode by viewModel.overwriteMode.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val skinPackage by viewModel.skinPackage.collectAsState()
    val analysisError by viewModel.analysisError.collectAsState()
    val isInstalling by viewModel.isInstalling.collectAsState()
    val installProgress by viewModel.installProgress.collectAsState()
    val installSummary by viewModel.installSummary.collectAsState()
    val testAccessResult by viewModel.testAccessResult.collectAsState()
    val isTestingAccess by viewModel.isTestingAccess.collectAsState()
    val overwritePrompt by viewModel.overwritePrompt.collectAsState()

    // Auto updater state
    val updateCheckState by marketplaceViewModel.updateCheckState.collectAsState()
    val updateDownloadState by marketplaceViewModel.updateDownloadState.collectAsState()
    var showManualUpdatePrompt by remember { mutableStateOf(false) }

    var showDestinationDialog by remember { mutableStateOf(false) }
    var showOverwriteDialog by remember { mutableStateOf(false) }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Not all providers support persistable permissions
            }
            onOpenZipPicker(uri)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (selectedTab == MainTab.INSTALLER) Icons.Default.FolderZip else Icons.Default.Storefront,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (selectedTab == MainTab.INSTALLER) "SkinScript" else "Skin Marketplace",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    // Update notification action
                    if (updateCheckState is UpdateCheckState.UpdateAvailable) {
                        IconButton(onClick = { showManualUpdatePrompt = true }) {
                            BadgedBox(badge = { Badge { Text("1") } }) {
                                Icon(
                                    imageVector = Icons.Default.SystemUpdate,
                                    contentDescription = "Update Available",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Refresh Action
                    IconButton(
                        onClick = {
                            if (selectedTab == MainTab.INSTALLER) {
                                viewModel.refreshShizuku()
                            } else {
                                marketplaceViewModel.loadInitialPage()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(imageVector = tab.icon, contentDescription = tab.title)
                        },
                        label = { Text(tab.title, fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                MainTab.INSTALLER -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // 1. Shizuku Status Card
                        ShizukuStatusCard(
                            state = shizukuState,
                            onRequestPermission = { viewModel.requestShizukuPermission() },
                            onRefresh = { viewModel.refreshShizuku() }
                        )

                        // 2. Destination Settings Card
                        DestinationSettingsCard(
                            destinationPath = destinationPath,
                            overwriteMode = overwriteMode,
                            testAccessResult = testAccessResult,
                            isTestingAccess = isTestingAccess,
                            isShizukuReady = shizukuState.isReady,
                            onEditDestination = { showDestinationDialog = true },
                            onEditOverwriteMode = { showOverwriteDialog = true },
                            onTestAccess = { viewModel.testDestinationAccess() }
                        )

                        // 3. ZIP Package Analysis Card
                        ZipPackageCard(
                            skinPackage = skinPackage,
                            isAnalyzing = isAnalyzing,
                            analysisError = analysisError,
                            onOpenZip = {
                                zipPickerLauncher.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/x-zip-compressed",
                                        "application/octet-stream",
                                        "*/*"
                                    )
                                )
                            },
                            onDismissError = { viewModel.dismissError() }
                        )

                        // 4. Installation Action & Progress Card
                        InstallActionCard(
                            skinPackage = skinPackage,
                            isShizukuReady = shizukuState.isReady,
                            isInstalling = isInstalling,
                            installProgress = installProgress,
                            onInstall = { viewModel.startInstall() }
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                MainTab.MARKETPLACE -> {
                    MarketplaceScreen(
                        viewModel = marketplaceViewModel,
                        onInstallZip = { uri ->
                            viewModel.loadZip(uri)
                            selectedTab = MainTab.INSTALLER
                            Toast.makeText(context, "Skin package loaded! Ready to install.", Toast.LENGTH_SHORT).show()
                        },
                        onOpenUpdate = { showManualUpdatePrompt = true }
                    )
                }
            }
        }
    }

    // Auto update dialog
    if (showManualUpdatePrompt) {
        val info = (updateCheckState as? UpdateCheckState.UpdateAvailable)?.info
        if (info != null) {
            AppUpdateDialog(
                info = info,
                downloadState = updateDownloadState,
                onConfirmUpdate = {
                    marketplaceViewModel.downloadAndInstallUpdate(info)
                },
                onDismiss = {
                    showManualUpdatePrompt = false
                }
            )
        }
    }

    // Edit Destination Dialog
    if (showDestinationDialog) {
        DestinationEditDialog(
            currentPath = destinationPath,
            onDismiss = { showDestinationDialog = false },
            onConfirm = { newPath ->
                viewModel.setDestination(newPath)
                showDestinationDialog = false
            }
        )
    }

    // Edit Overwrite Mode Dialog
    if (showOverwriteDialog) {
        OverwriteModeDialog(
            currentMode = overwriteMode,
            onDismiss = { showOverwriteDialog = false },
            onSelect = { mode ->
                viewModel.setOverwrite(mode)
                showOverwriteDialog = false
            }
        )
    }

    // Overwrite Confirmation Prompt Dialog
    overwritePrompt?.let { promptData ->
        OverwritePromptDialog(
            data = promptData,
            onDecision = { decision ->
                viewModel.respondToOverwritePrompt(decision)
            }
        )
    }

    // Installation Summary Dialog
    installSummary?.let { summary ->
        InstallSummaryDialog(
            summary = summary,
            onDismiss = { viewModel.dismissSummary() }
        )
    }
}

@Composable
fun ShizukuStatusCard(
    state: ShizukuState,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit
) {
    val (statusColor, containerColor, icon) = when {
        state.isReady -> Triple(
            Color(0xFF2E7D32),
            Color(0xFFE8F5E9),
            Icons.Default.CheckCircle
        )
        state.isRunning && !state.isGranted -> Triple(
            Color(0xFFE65100),
            Color(0xFFFFF3E0),
            Icons.Default.Warning
        )
        else -> Triple(
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            Icons.Default.Close
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Shizuku Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                    Text(
                        text = state.statusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.isRunning && !state.isGranted) {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(containerColor = statusColor)
                ) {
                    Icon(imageVector = Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Grant Permission")
                }
            } else if (!state.isRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onRefresh) {
                        Text("Recheck")
                    }
                }
            }
        }
    }
}

@Composable
fun DestinationSettingsCard(
    destinationPath: String,
    overwriteMode: OverwriteMode,
    testAccessResult: String?,
    isTestingAccess: Boolean,
    isShizukuReady: Boolean,
    onEditDestination: () -> Unit,
    onEditOverwriteMode: () -> Unit,
    onTestAccess: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Destination & Policies",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(10.dp)
            ) {
                Text(
                    text = "TARGET ASSETS PATH:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = destinationPath,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Overwrite Policy",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = overwriteMode.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                FilledTonalButton(onClick = onEditOverwriteMode) {
                    Text("Change")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onEditDestination,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit Path")
                }

                Button(
                    onClick = onTestAccess,
                    enabled = isShizukuReady && !isTestingAccess,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isTestingAccess) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test Access")
                    }
                }
            }

            testAccessResult?.let { result ->
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.startsWith("✓")) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
fun ZipPackageCard(
    skinPackage: SkinPackage?,
    isAnalyzing: Boolean,
    analysisError: String?,
    onOpenZip: () -> Unit,
    onDismissError: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.FolderZip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Skin Package",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                FilledTonalButton(onClick = onOpenZip) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (skinPackage == null) "Open ZIP" else "Change ZIP")
                }
            }

            if (isAnalyzing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Analyzing ZIP archive entries...")
                }
            } else if (skinPackage != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = skinPackage.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CategoryBadge(
                            icon = Icons.Default.Palette,
                            title = "Art",
                            count = skinPackage.artFiles.size,
                            modifier = Modifier.weight(1f)
                        )
                        CategoryBadge(
                            icon = Icons.Default.GraphicEq,
                            title = "Audio",
                            count = skinPackage.audioFiles.size,
                            modifier = Modifier.weight(1f)
                        )
                        CategoryBadge(
                            icon = Icons.Default.Widgets,
                            title = "UI",
                            count = skinPackage.uiFiles.size,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Total detected assets: ${skinPackage.totalFiles} files",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = skinPackage.formattedTotalSize,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "No package selected",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Use 'Open with' from your download manager or click 'Open ZIP' above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            analysisError?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismissError, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryBadge(
    icon: ImageVector,
    title: String,
    count: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (count > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (count > 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "$count files",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun InstallActionCard(
    skinPackage: SkinPackage?,
    isShizukuReady: Boolean,
    isInstalling: Boolean,
    installProgress: com.example.skinscript.data.InstallProgress,
    onInstall: () -> Unit
) {
    val canInstall = skinPackage != null && skinPackage.hasValidAssets && isShizukuReady && !isInstalling

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isInstalling) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Installing files...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${installProgress.completedCount} / ${installProgress.totalCount} (${installProgress.percentage}%)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (installProgress.isIndeterminate) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { installProgress.fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Text(
                        text = "Writing: ${installProgress.currentFileName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Button(
                    onClick = onInstall,
                    enabled = canInstall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Install Skin Assets",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (!isShizukuReady) {
                    Text(
                        text = "⚠ Shizuku service must be running and authorized to perform installation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (skinPackage == null) {
                    Text(
                        text = "Select or share a skin ZIP file to begin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun DestinationEditDialog(
    currentPath: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentPath) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configure Destination Path") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Specify the target directory where the assets folder will be extracted:",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Destination Directory") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun OverwriteModeDialog(
    currentMode: OverwriteMode,
    onDismiss: () -> Unit,
    onSelect: (OverwriteMode) -> Unit
) {
    var selected by remember { mutableStateOf(currentMode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Overwrite Policy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OverwriteMode.values().forEach { mode ->
                    Surface(
                        onClick = { selected = mode },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected == mode) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            RadioButton(
                                selected = selected == mode,
                                onClick = { selected = mode }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = mode.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = mode.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSelect(selected) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun OverwritePromptDialog(
    data: OverwritePromptData,
    onDecision: (OverwriteDecision) -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDecision(OverwriteDecision.SKIP) },
        title = { Text("Existing File Detected") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "The file '${data.fileName}' already exists in the destination folder:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = data.targetPath,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "How would you like to proceed?",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { onDecision(OverwriteDecision.OVERWRITE) }) {
                    Text("Overwrite")
                }
                FilledTonalButton(onClick = { onDecision(OverwriteDecision.OVERWRITE_ALL) }) {
                    Text("Overwrite All")
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { onDecision(OverwriteDecision.SKIP) }) {
                    Text("Skip")
                }
                TextButton(onClick = { onDecision(OverwriteDecision.SKIP_ALL) }) {
                    Text("Skip All")
                }
            }
        }
    )
}

@Composable
fun InstallSummaryDialog(
    summary: InstallSummary,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (summary.isAllSuccessful) Icons.Default.CheckCircle else Icons.Default.Info,
                    contentDescription = null,
                    tint = if (summary.isAllSuccessful) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (summary.isAllSuccessful) "Installation Complete" else "Installation Finished")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Summary of extracted assets:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("• Total files processed: ${summary.total}")
                        Text("• Installed successfully: ${summary.success}", color = Color(0xFF2E7D32), fontWeight = FontWeight.SemiBold)
                        Text("• Skipped (existed): ${summary.skipped}", color = Color(0xFFE65100))
                        Text("• Failed: ${summary.failed}", color = if (summary.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("• Time elapsed: ${summary.durationMs / 1000.0}s")
                    }
                }

                if (summary.errors.isNotEmpty()) {
                    Text(
                        text = "Errors encountered:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        summary.errors.forEach { err ->
                            Text(
                                text = "• $err",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}