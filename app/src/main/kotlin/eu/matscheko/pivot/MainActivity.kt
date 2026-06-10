package eu.matscheko.pivot

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import eu.matscheko.pivot.egress.EgressService
import eu.matscheko.pivot.settings.AppSettings
import eu.matscheko.pivot.settings.SettingsRepository
import eu.matscheko.pivot.vpn.PivotVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dot colour used in the bottom nav to show each engine's running state. */
private val RunningGreen = Color(0xFF34A853)

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepo: SettingsRepository

    private val egressUi = MutableStateFlow<ServerState>(ServerState.Off)
    private val vpnUi = MutableStateFlow<VpnState>(VpnState.Off)

    private var egressService: EgressService? = null
    private var vpnService: PivotVpnService? = null
    private var egressJob: Job? = null
    private var vpnJob: Job? = null

    private val egressConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? EgressService.LocalBinder)?.service ?: return
            egressService = svc
            egressJob = lifecycleScope.launch { svc.state.collect { egressUi.value = it } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            egressJob?.cancel(); egressJob = null; egressService = null
        }
    }

    private val vpnConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? PivotVpnService.LocalBinder)?.service ?: return
            vpnService = svc
            vpnJob = lifecycleScope.launch { svc.state.collect { vpnUi.value = it } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnJob?.cancel(); vpnJob = null; vpnService = null
        }
    }

    private var pendingAfterNotif: (() -> Unit)? = null

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            pendingAfterNotif?.invoke()
            pendingAfterNotif = null
        }

    private val vpnPrepareLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) sendStartVpn()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepo = SettingsRepository(this)
        enableEdgeToEdge()
        setContent {
            PivotTheme {
                val egress by egressUi.collectAsStateWithLifecycle()
                val vpn by vpnUi.collectAsStateWithLifecycle()
                MainScreen(
                    egress = egress,
                    vpn = vpn,
                    settingsRepo = settingsRepo,
                    onToggleEgress = { on -> if (on) requestStartEgress() else sendStopEgress() },
                    onToggleVpn = { on -> if (on) requestStartVpn() else sendStopVpn() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, EgressService::class.java), egressConnection, Context.BIND_AUTO_CREATE)
        bindService(Intent(this, PivotVpnService::class.java), vpnConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        egressJob?.cancel(); egressJob = null
        vpnJob?.cancel(); vpnJob = null
        runCatching { unbindService(egressConnection) }
        runCatching { unbindService(vpnConnection) }
        egressService = null
        vpnService = null
    }

    private fun ensureNotif(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAfterNotif = action
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    private fun requestStartEgress() = ensureNotif { sendStartEgress() }

    private fun requestStartVpn() = ensureNotif {
        val prepare = VpnService.prepare(this)
        if (prepare != null) vpnPrepareLauncher.launch(prepare) else sendStartVpn()
    }

    private fun sendStartEgress() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, EgressService::class.java).setAction(EgressService.ACTION_START),
        )
    }

    private fun sendStopEgress() {
        startService(Intent(this, EgressService::class.java).setAction(EgressService.ACTION_STOP))
    }

    private fun sendStartVpn() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, PivotVpnService::class.java).setAction(PivotVpnService.ACTION_START),
        )
    }

    private fun sendStopVpn() {
        startService(Intent(this, PivotVpnService::class.java).setAction(PivotVpnService.ACTION_STOP))
    }
}

@Composable
private fun PivotTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private enum class Tab(val title: String, val label: String, val icon: Int) {
    SETUP("Pivot setup", "Setup", R.drawable.ic_tab_setup),
    EGRESS("Egress proxy", "Egress", R.drawable.ic_tab_egress),
    VPN("VPN capture", "VPN", R.drawable.ic_tab_vpn),
    OPTIONS("App options", "Options", R.drawable.ic_tab_options),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    egress: ServerState,
    vpn: VpnState,
    settingsRepo: SettingsRepository,
    onToggleEgress: (Boolean) -> Unit,
    onToggleVpn: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bindOptions = remember { NetUtils.bindOptions() }

    var selectedTab by remember { mutableIntStateOf(Tab.SETUP.ordinal) }
    var showAbout by remember { mutableStateOf(false) }

    var loaded by remember { mutableStateOf(false) }
    // Egress
    var egressPort by remember { mutableStateOf("1080") }
    var egressBind by remember { mutableStateOf(ADDRESS_ALL) }
    var egressAuth by remember { mutableStateOf(false) }
    var egressUser by remember { mutableStateOf("") }
    var egressPass by remember { mutableStateOf("") }
    var startOnBoot by remember { mutableStateOf(false) }
    // VPN
    var upstreamHost by remember { mutableStateOf("127.0.0.1") }
    var upstreamPort by remember { mutableStateOf("8080") }
    var upstreamType by remember { mutableStateOf(AppSettings.UPSTREAM_SOCKS5) }
    var upstreamAuth by remember { mutableStateOf(false) }
    var upstreamUser by remember { mutableStateOf("") }
    var upstreamPass by remember { mutableStateOf("") }
    var bypassDomains by remember { mutableStateOf("") }
    var dnsOverProxy by remember { mutableStateOf(true) }
    var directUseUnderlying by remember { mutableStateOf(true) }
    var directDns by remember { mutableStateOf("8.8.8.8") }
    var directDnsPort by remember { mutableStateOf("53") }
    var appFilterMode by remember { mutableStateOf(AppSettings.APP_FILTER_OFF) }
    var appList by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAppPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val s = settingsRepo.settings.first()
        egressPort = s.egressPort.toString()
        egressBind = s.egressBindAddress
        egressAuth = s.egressAuthEnabled
        egressUser = s.egressUsername
        egressPass = s.egressPassword
        startOnBoot = s.startEgressOnBoot
        upstreamHost = s.upstreamHost
        upstreamPort = s.upstreamPort.toString()
        upstreamType = s.upstreamType
        upstreamAuth = s.upstreamAuthEnabled
        upstreamUser = s.upstreamUsername
        upstreamPass = s.upstreamPassword
        bypassDomains = s.bypassDomains
        dnsOverProxy = s.dnsOverProxy
        directUseUnderlying = s.directUseUnderlyingDns
        directDns = s.directDns
        directDnsPort = s.directDnsPort.toString()
        appFilterMode = s.appFilterMode
        appList = s.appList
        loaded = true
    }

    fun persist(transform: (AppSettings) -> AppSettings) {
        scope.launch { settingsRepo.update(transform) }
    }

    val egressRunning = egress is ServerState.Running || egress is ServerState.Starting
    val vpnRunning = vpn is VpnState.Running || vpn is VpnState.Starting

    if (showAbout) {
        AboutScreen(onBack = { showAbout = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Tab.entries[selectedTab].title) },
                actions = {
                    if (Tab.entries[selectedTab] == Tab.OPTIONS) {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("About") },
                                onClick = { menuOpen = false; showAbout = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    val running = when (tab) {
                        Tab.EGRESS -> egressRunning
                        Tab.VPN -> vpnRunning
                        else -> null
                    }
                    NavigationBarItem(
                        selected = selectedTab == tab.ordinal,
                        onClick = { selectedTab = tab.ordinal },
                        icon = {
                            if (running != null) {
                                BadgedBox(badge = {
                                    Badge(
                                        containerColor = if (running) RunningGreen
                                        else MaterialTheme.colorScheme.outline,
                                    )
                                }) {
                                    Icon(painterResource(tab.icon), contentDescription = tab.label)
                                }
                            } else {
                                Icon(painterResource(tab.icon), contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (Tab.entries[selectedTab]) {
                Tab.SETUP -> SetupContent(egress, vpn)

                Tab.EGRESS -> {
                    EgressSwitchCard(state = egress, onToggle = onToggleEgress)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Egress SOCKS5 proxy", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = egressPort,
                                onValueChange = { input ->
                                    val d = input.filter { it.isDigit() }.take(5)
                                    egressPort = d
                                    d.toIntOrNull()?.let { p -> if (p in 1..65535) persist { it.copy(egressPort = p) } }
                                },
                                label = { Text("Listen port") },
                                singleLine = true,
                                enabled = !egressRunning,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            BindInterfaceDropdown(
                                options = bindOptions,
                                selected = egressBind,
                                enabled = !egressRunning,
                                onSelected = { egressBind = it; persist { s -> s.copy(egressBindAddress = it) } },
                            )
                            HorizontalDivider()
                            Text("Authentication", style = MaterialTheme.typography.titleSmall)
                            Column(Modifier.selectableGroup()) {
                                AuthOption("None", !egressAuth, !egressRunning) {
                                    egressAuth = false; persist { it.copy(egressAuthEnabled = false) }
                                }
                                AuthOption("Username + password", egressAuth, !egressRunning) {
                                    egressAuth = true; persist { it.copy(egressAuthEnabled = true) }
                                }
                            }
                            if (egressAuth) {
                                OutlinedTextField(
                                    value = egressUser,
                                    onValueChange = { egressUser = it; persist { s -> s.copy(egressUsername = it) } },
                                    label = { Text("Username") },
                                    singleLine = true,
                                    enabled = !egressRunning,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = egressPass,
                                    onValueChange = { egressPass = it; persist { s -> s.copy(egressPassword = it) } },
                                    label = { Text("Password") },
                                    singleLine = true,
                                    enabled = !egressRunning,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    if (egress is ServerState.Running) {
                        EgressStatusCard(egress)
                    }
                }

                Tab.VPN -> {
                    VpnSwitchCard(vpn = vpn, onToggle = onToggleVpn)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Upstream proxy (e.g. Burp Suite)", style = MaterialTheme.typography.titleMedium)
                            Text("Proxy type", style = MaterialTheme.typography.titleSmall)
                            Column(Modifier.selectableGroup()) {
                                AuthOption(
                                    "SOCKS5",
                                    upstreamType == AppSettings.UPSTREAM_SOCKS5,
                                    !vpnRunning,
                                ) {
                                    upstreamType = AppSettings.UPSTREAM_SOCKS5
                                    persist { it.copy(upstreamType = AppSettings.UPSTREAM_SOCKS5) }
                                }
                                AuthOption(
                                    "HTTP/S (CONNECT) — required for Burp Suite",
                                    upstreamType == AppSettings.UPSTREAM_HTTP,
                                    !vpnRunning,
                                ) {
                                    upstreamType = AppSettings.UPSTREAM_HTTP
                                    persist { it.copy(upstreamType = AppSettings.UPSTREAM_HTTP) }
                                }
                            }
                            OutlinedTextField(
                                value = upstreamHost,
                                onValueChange = { upstreamHost = it; persist { s -> s.copy(upstreamHost = it) } },
                                label = { Text("Proxy host") },
                                singleLine = true,
                                enabled = !vpnRunning,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = upstreamPort,
                                onValueChange = { input ->
                                    val d = input.filter { it.isDigit() }.take(5)
                                    upstreamPort = d
                                    d.toIntOrNull()?.let { p -> if (p in 1..65535) persist { it.copy(upstreamPort = p) } }
                                },
                                label = { Text("Proxy port") },
                                singleLine = true,
                                enabled = !vpnRunning,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            CheckboxRow(
                                label = "Proxy requires username/password",
                                checked = upstreamAuth,
                                enabled = !vpnRunning,
                            ) { upstreamAuth = it; persist { s -> s.copy(upstreamAuthEnabled = it) } }
                            if (upstreamAuth) {
                                OutlinedTextField(
                                    value = upstreamUser,
                                    onValueChange = { upstreamUser = it; persist { s -> s.copy(upstreamUsername = it) } },
                                    label = { Text("Username") },
                                    singleLine = true,
                                    enabled = !vpnRunning,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = upstreamPass,
                                    onValueChange = { upstreamPass = it; persist { s -> s.copy(upstreamPassword = it) } },
                                    label = { Text("Password") },
                                    singleLine = true,
                                    enabled = !vpnRunning,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            HorizontalDivider()
                            SwitchRow(
                                title = "DNS over SOCKS5 (fake-IP)",
                                subtitle = "Resolve names on the proxy/egress side, not the device. Keep on for the pivot.",
                                checked = dnsOverProxy,
                                enabled = !vpnRunning,
                            ) { dnsOverProxy = it; persist { s -> s.copy(dnsOverProxy = it) } }
                            if (!dnsOverProxy) {
                                SwitchRow(
                                    title = "Use underlying network's DNS",
                                    subtitle = "Resolve via the phone's real network resolver (bypasses the VPN).",
                                    checked = directUseUnderlying,
                                    enabled = !vpnRunning,
                                ) { directUseUnderlying = it; persist { s -> s.copy(directUseUnderlyingDns = it) } }
                                if (!directUseUnderlying) {
                                    OutlinedTextField(
                                        value = directDns,
                                        onValueChange = { directDns = it; persist { s -> s.copy(directDns = it) } },
                                        label = { Text("DNS server (IP)") },
                                        singleLine = true,
                                        enabled = !vpnRunning,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = directDnsPort,
                                        onValueChange = { input ->
                                            val d = input.filter { it.isDigit() }.take(5)
                                            directDnsPort = d
                                            d.toIntOrNull()?.let { p -> if (p in 1..65535) persist { it.copy(directDnsPort = p) } }
                                        },
                                        label = { Text("DNS port") },
                                        singleLine = true,
                                        enabled = !vpnRunning,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                    BypassDomainsCard(
                        value = bypassDomains,
                        enabled = !vpnRunning,
                        onValueChange = { bypassDomains = it; persist { s -> s.copy(bypassDomains = it) } },
                    )
                    PerAppFilterCard(
                        mode = appFilterMode,
                        selectedCount = appList.size,
                        enabled = !vpnRunning,
                        onModeChange = { appFilterMode = it; persist { s -> s.copy(appFilterMode = it) } },
                        onEditApps = { showAppPicker = true },
                    )
                    if (showAppPicker) {
                        AppPickerDialog(
                            initialSelection = appList,
                            onDismiss = { showAppPicker = false },
                            onConfirm = { sel ->
                                appList = sel
                                persist { s -> s.copy(appList = sel) }
                                showAppPicker = false
                            },
                        )
                    }
                }

                Tab.OPTIONS -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            CheckboxRow(
                                label = "Start egress proxy on boot",
                                checked = startOnBoot,
                                subtitle = "The VPN cannot auto-start (it needs user consent).",
                            ) { startOnBoot = it; persist { s -> s.copy(startEgressOnBoot = it) } }
                        }
                    }
                    BatteryOptimizationCard()
                }
            }
        }
    }
}

private const val PIVOT_LICENSE =
    "Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0). " +
        "This program is free software: you can redistribute it and/or modify it under the " +
        "terms of that license, and it comes with NO WARRANTY."

private const val PIVOT_DISCLAIMER =
    "This app captures and proxies network traffic for authorised security testing and " +
        "development only. The author is not liable for any damage, data loss, or misuse " +
        "arising from its use. Only use it on networks and devices you are permitted to test."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // The launcher icon is an adaptive-icon XML that painterResource can't load, so
    // composite the real installed icon to a bitmap instead (same trick as elsewhere).
    val iconBitmap = remember(context) {
        context.packageManager.getApplicationIcon(context.packageName)
            .toBitmap(192, 192).asImageBitmap()
    }
    val version = remember(context) {
        runCatching {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode
            } else {
                @Suppress("DEPRECATION") pkg.versionCode.toLong()
            }
            "Version ${pkg.versionName} ($code)"
        }.getOrDefault("")
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About this app") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(
                bitmap = iconBitmap,
                contentDescription = null,
                modifier = Modifier.size(96.dp).padding(bottom = 8.dp),
            )
            Text("Pivot Proxy", style = MaterialTheme.typography.headlineMedium)
            if (version.isNotEmpty()) {
                Text(version, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Copyright © 2026 Matscheko — built in Kotlin & Jetpack Compose",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                PIVOT_LICENSE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                PIVOT_DISCLAIMER,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SetupContent(egress: ServerState, vpn: VpnState) {
    StatusDashboardCard(egress, vpn)
    PivotHelpCard()
}

@Composable
private fun StatusDashboardCard(egress: ServerState, vpn: VpnState) {
    val egressText = when (egress) {
        ServerState.Off -> "Off"
        ServerState.Starting -> "Starting…"
        is ServerState.Running -> {
            val where = egress.boundAddresses.firstOrNull() ?: ADDRESS_ALL
            "Running on $where:${egress.port} · ${egress.connections} conn"
        }
        is ServerState.Error -> "Error: ${egress.message}"
    }
    val vpnText = when (vpn) {
        VpnState.Off -> "Off"
        VpnState.Starting -> "Starting…"
        is VpnState.Running -> {
            val dns = if (vpn.dnsOverProxy) "DNS via proxy" else "direct DNS"
            val proto = if (vpn.upstreamType == AppSettings.UPSTREAM_HTTP) "HTTP" else "SOCKS5"
            "Capturing → ${vpn.upstream} ($proto) · $dns"
        }
        is VpnState.Error -> "Error: ${vpn.message}"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Status", style = MaterialTheme.typography.titleMedium)
            StatusRow("Egress proxy", egressText, egress is ServerState.Running)
            StatusRow("VPN capture", vpnText, vpn is VpnState.Running)
        }
    }
}

@Composable
private fun StatusRow(name: String, detail: String, on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Badge(
            containerColor = if (on) RunningGreen else MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VpnSwitchCard(vpn: VpnState, onToggle: (Boolean) -> Unit) {
    val running = vpn is VpnState.Running
    val status = when (vpn) {
        VpnState.Off -> "Off"
        VpnState.Starting -> "Starting…"
        is VpnState.Running -> "Capturing → ${vpn.upstream}"
        is VpnState.Error -> "Error"
    }
    EngineSwitchCard("VPN capture", status, running, vpn is VpnState.Error,
        (vpn as? VpnState.Error)?.message, onToggle,
        switchOn = vpn is VpnState.Running || vpn is VpnState.Starting)
}

@Composable
private fun EgressSwitchCard(state: ServerState, onToggle: (Boolean) -> Unit) {
    val running = state is ServerState.Running
    val status = when (state) {
        ServerState.Off -> "Off"
        ServerState.Starting -> "Starting…"
        is ServerState.Running -> "Running"
        is ServerState.Error -> "Error"
    }
    EngineSwitchCard("Egress proxy", status, running, state is ServerState.Error,
        (state as? ServerState.Error)?.message, onToggle,
        switchOn = state is ServerState.Running || state is ServerState.Starting)
}

@Composable
private fun EngineSwitchCard(
    title: String,
    status: String,
    running: Boolean,
    isError: Boolean,
    errorMessage: String?,
    onToggle: (Boolean) -> Unit,
    switchOn: Boolean,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (running) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = switchOn, onCheckedChange = onToggle)
            }
            if (isError && errorMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BindInterfaceDropdown(
    options: List<InterfaceOption>,
    selected: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.address == selected }?.label ?: selected
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Bind interface") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { onSelected(option.address); expanded = false },
                )
            }
        }
    }
}

/** A radio option whose whole row is tappable (not just the bullet). */
@Composable
private fun AuthOption(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label)
    }
}

/** A checkbox whose whole row is tappable; optional second line of description. */
@Composable
private fun CheckboxRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            ),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        if (subtitle == null) {
            Text(label)
        } else {
            Column(Modifier.weight(1f)) {
                Text(label)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A switch row (title + optional subtitle) where tapping anywhere toggles it. */
@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun EgressStatusCard(state: ServerState.Running) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Egress status", style = MaterialTheme.typography.titleMedium)
            Text("Listening on:", style = MaterialTheme.typography.bodySmall)
            state.boundAddresses.forEach { addr ->
                SelectionContainer {
                    Text(
                        "$addr:${state.port}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            HorizontalDivider()
            Text("Active connections: ${state.connections}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PivotHelpCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("How the pivot works", style = MaterialTheme.typography.titleMedium)
            Text(
                "Device traffic → VPN capture → upstream proxy (Burp Suite) → the phone's " +
                    "own egress proxy → out the phone's interface. Names are resolved on " +
                    "the egress side, so the origin sees the phone, not the laptop.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text("Setup steps", style = MaterialTheme.typography.titleSmall)
            Text(
                "1) Egress tab: start the egress proxy.\n" +
                    "2) VPN tab: set the upstream proxy to Burp Suite. Choose proxy type " +
                    "HTTP/S (Burp's proxy listener only accepts HTTP/S, not SOCKS5). Keep " +
                    "DNS over SOCKS5 on.\n" +
                    "3) In Burp Suite, chain its upstream/SOCKS proxy back to this phone's " +
                    "egress proxy.\n" +
                    "4) VPN tab: start VPN capture and accept the consent dialog.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text("Reaching the phone", style = MaterialTheme.typography.titleSmall)
            Text(
                "If the phone has a Wi-Fi/LAN IP the computer can reach, use that egress " +
                    "address in Burp Suite. On mobile data the phone usually isn't reachable, so " +
                    "tunnel over USB with adb (run on the computer):",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    "# phone → Burp Suite (use upstream 127.0.0.1:8080)\n" +
                        "adb reverse tcp:8080 tcp:8080\n\n" +
                        "# computer → phone egress (chain Burp Suite to 127.0.0.1:1080)\n" +
                        "adb forward tcp:1080 tcp:1080\n\n" +
                        "# optional: expose the egress on all interfaces\n" +
                        "socat TCP-LISTEN:1080,bind=0.0.0.0,fork,reuseaddr \\\n" +
                        "      TCP:127.0.0.1:1080",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun BypassDomainsCard(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bypass domains (skip the proxy)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Listed hosts connect straight to the internet instead of through " +
                    "the proxy — handy for an untrusted dependency that breaks under " +
                    "interception. One per line; subdomains match (example.com also " +
                    "covers api.example.com). Requires DNS over SOCKS5.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("Bypass domains") },
                enabled = enabled,
                singleLine = false,
                minLines = 2,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PerAppFilterCard(
    mode: String,
    selectedCount: Int,
    enabled: Boolean,
    onModeChange: (String) -> Unit,
    onEditApps: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Per-app capture", style = MaterialTheme.typography.titleMedium)
            Text(
                "Choose which apps the VPN captures. Apps that aren't captured go straight " +
                    "to the internet, untouched by the proxy — handy to keep a pinned " +
                    "dependency out of scope. Works in any DNS mode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.selectableGroup()) {
                AuthOption("Capture all apps", mode == AppSettings.APP_FILTER_OFF, enabled) {
                    onModeChange(AppSettings.APP_FILTER_OFF)
                }
                AuthOption("Only selected apps", mode == AppSettings.APP_FILTER_INCLUDE, enabled) {
                    onModeChange(AppSettings.APP_FILTER_INCLUDE)
                }
                AuthOption("All apps except selected", mode == AppSettings.APP_FILTER_EXCLUDE, enabled) {
                    onModeChange(AppSettings.APP_FILTER_EXCLUDE)
                }
            }
            if (mode != AppSettings.APP_FILTER_OFF) {
                Button(onClick = onEditApps, enabled = enabled) {
                    Text("Select apps ($selectedCount)")
                }
            }
        }
    }
}

@Composable
private fun AppPickerDialog(
    initialSelection: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val apps by produceState<List<AppInfo>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { Apps.networkApps(context) }
    }
    var query by remember { mutableStateOf("") }
    val selection = remember { mutableStateListOf<String>().apply { addAll(initialSelection) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(selection.toSet()) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Select apps") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val list = apps
                if (list == null) {
                    Box(
                        Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                } else {
                    val filtered = remember(query, list) {
                        if (query.isBlank()) list
                        else list.filter {
                            it.label.contains(query, true) || it.packageName.contains(query, true)
                        }
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            val checked = app.packageName in selection
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                    onValueChange = { c ->
                                        if (c) selection.add(app.packageName)
                                        else selection.remove(app.packageName)
                                    },
                                ),
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun BatteryOptimizationCard() {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Keep running in the background", style = MaterialTheme.typography.titleSmall)
            Text(
                "Some vendors (Samsung, Xiaomi, Huawei, OnePlus) aggressively kill " +
                    "background services. If a service stops unexpectedly, exclude this " +
                    "app from battery optimization.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }) {
                Text("Battery optimization settings")
            }
        }
    }
}
