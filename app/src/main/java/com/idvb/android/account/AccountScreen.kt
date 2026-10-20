package com.idvb.android.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Page { Login, Register, Reset, Verify }
private enum class Flow(val confirm: String, val resend: String, val origin: Page) {
    Login("login.confirm", "login", Page.Login),
    Register("register.confirm", "register.start", Page.Register),
    Reset("password_reset.confirm", "password_reset.request", Page.Reset),
}

private val REGISTER_DOMAINS = setOf("qq.com", "gmail.com")
private val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")

@Composable
internal fun AccountScreen(accounts: AccountAccess, onExit: () -> Unit) {
    val state by accounts.state.collectAsState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    // Secrets and unfinished email verification are deliberately not saved in Activity state.
    var page by remember { mutableStateOf(Page.Login) }
    var flow by remember { mutableStateOf(Flow.Login) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var cooldown by remember { mutableIntStateOf(0) }
    LaunchedEffect(cooldown) { if (cooldown > 0) { delay(1_000); cooldown-- } }

    fun go(target: Page) {
        page = target; error = ""; info = ""; code = ""
        if (target != Page.Login) { password = ""; confirmPassword = "" }
    }
    fun back() { if (page == Page.Verify) go(flow.origin) else go(Page.Login) }
    BackHandler { if (!busy) { if (page == Page.Login) onExit() else back() } }

    fun localError(operation: String, address: String): String? = when {
        !address.matches(EMAIL) -> "请填写有效的邮箱地址。"
        operation == "register.start" && address.substringAfter('@').lowercase() !in REGISTER_DOMAINS ->
            "注册目前仅支持 QQ 邮箱或 Gmail。"
        operation == "register.start" && name.trim().length !in 2..32 -> "昵称需要 2–32 个字符。"
        operation in listOf("register.start", "password_reset.confirm") && password.length !in 10..128 ->
            "密码需为 10–128 位。"
        operation in listOf("register.start", "password_reset.confirm") && password != confirmPassword ->
            "两次输入的密码不一致。"
        operation.startsWith("login") && password.isEmpty() -> "请输入密码。"
        operation.endsWith("confirm") && !code.matches(Regex("[0-9]{6}")) -> "请输入邮件中的 6 位验证码。"
        else -> null
    }

    fun submit(operation: String) {
        if (busy) return
        val address = email.trim()
        localError(operation, address)?.let { error = it; info = ""; return }
        val fields = buildMap {
            put("email", address)
            if (operation.startsWith("login") || operation.endsWith("confirm")) put("password", password)
            if (operation.endsWith("confirm")) put("code", code)
            if (operation == "register.confirm") put("displayName", name.trim())
        }
        focus.clearFocus()
        busy = true
        error = ""; info = ""
        scope.launch {
            try {
                when (val result = accounts.authenticate(operation, fields)) {
                    "verification_required", "code_sent" -> {
                        flow = when (operation) {
                            "login" -> Flow.Login
                            "register.start" -> Flow.Register
                            else -> Flow.Reset
                        }
                        page = Page.Verify
                        code = ""
                        cooldown = 60
                        info = "验证码已发送，如未收到请检查垃圾邮件。"
                    }
                    else -> {
                        password = ""; confirmPassword = ""; code = ""
                        page = Page.Login
                        info = result
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = if (failure is AccountFailure) failure.message.orEmpty()
                    else "操作未完成，请检查网络和系统时间后重试。"
            } finally { busy = false }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            if (page == Page.Login) {
                Spacer(Modifier.height(48.dp))
                Text("IDVB", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("登录账户", style = MaterialTheme.typography.headlineMedium)
            } else {
                IconButton(onClick = ::back, enabled = !busy, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
                Spacer(Modifier.height(8.dp))
                Text(when (page) {
                    Page.Register -> "创建账户"
                    Page.Reset -> "重置密码"
                    else -> "验证邮箱"
                }, style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(when (page) {
                Page.Login -> "手机与 PC 使用同一账户。"
                Page.Register -> "注册后即可在手机和 PC 上登录。"
                Page.Reset -> "输入注册邮箱，我们会发送验证码。"
                Page.Verify -> "验证码已发送至 ${email.trim()}"
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))

            // Server-side account status (expired, banned, offline) is only relevant on the login page.
            if (page == Page.Login && state.message != AccountState().message && !state.authorized) {
                StatusBanner(state.message) {
                    if (state.hasSession) {
                        TextButton(onClick = accounts::refresh, enabled = !busy) { Text("重新验证") }
                        TextButton(onClick = accounts::logout, enabled = !busy) { Text("退出登录") }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            val fieldGap = Modifier.height(12.dp)
            when (page) {
                Page.Login -> {
                    EmailField(email, { email = it }, !busy) { focus.moveFocus(FocusDirection.Down) }
                    Spacer(fieldGap)
                    PasswordField(password, { password = it }, "密码", !busy, ImeAction.Done) { submit("login") }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        TextButton(onClick = { go(Page.Reset) }, enabled = !busy) { Text("忘记密码？") }
                    }
                }
                Page.Register -> {
                    TextInput(name, { name = it }, "昵称", Icons.Outlined.Person, !busy,
                        supporting = "2–32 个字符") { focus.moveFocus(FocusDirection.Down) }
                    Spacer(fieldGap)
                    EmailField(email, { email = it }, !busy, supporting = "支持 QQ 邮箱或 Gmail") {
                        focus.moveFocus(FocusDirection.Down)
                    }
                    Spacer(fieldGap)
                    PasswordField(password, { password = it }, "密码", !busy, ImeAction.Next,
                        supporting = "10–128 位") { focus.moveFocus(FocusDirection.Down) }
                    Spacer(fieldGap)
                    PasswordField(confirmPassword, { confirmPassword = it }, "确认密码", !busy, ImeAction.Done) {
                        submit("register.start")
                    }
                }
                Page.Reset -> EmailField(email, { email = it }, !busy, ImeAction.Done) { submit("password_reset.request") }
                Page.Verify -> {
                    TextInput(code, { code = it.filter(Char::isDigit).take(6) }, "6 位验证码", Icons.Outlined.Pin, !busy,
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = if (flow == Flow.Reset) ImeAction.Next else ImeAction.Done,
                    ) { if (flow == Flow.Reset) focus.moveFocus(FocusDirection.Down) else submit(flow.confirm) }
                    if (flow == Flow.Reset) {
                        Spacer(fieldGap)
                        PasswordField(password, { password = it }, "新密码", !busy, ImeAction.Next,
                            supporting = "10–128 位") { focus.moveFocus(FocusDirection.Down) }
                        Spacer(fieldGap)
                        PasswordField(confirmPassword, { confirmPassword = it }, "确认新密码", !busy, ImeAction.Done) {
                            submit(flow.confirm)
                        }
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        TextButton(onClick = { submit(flow.resend) }, enabled = !busy && cooldown == 0) {
                            Text(if (cooldown > 0) "${cooldown} 秒后重新发送" else "重新发送验证码")
                        }
                    }
                }
            }

            if (error.isNotBlank() || info.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(error.ifBlank { info }, style = MaterialTheme.typography.bodyMedium,
                    color = if (error.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    submit(when (page) {
                        Page.Login -> "login"
                        Page.Register -> "register.start"
                        Page.Reset -> "password_reset.request"
                        Page.Verify -> flow.confirm
                    })
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text(when (page) {
                    Page.Login -> "登录"
                    Page.Register, Page.Reset -> "发送验证码"
                    Page.Verify -> if (flow == Flow.Reset) "重置密码" else "确认"
                }, style = MaterialTheme.typography.titleMedium)
            }

            if (page == Page.Login) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("还没有账户？", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { go(Page.Register) }, enabled = !busy) { Text("注册") }
                }
            }
            Spacer(Modifier.weight(1f).heightIn(min = 32.dp))
            Text("账户问题或封禁申诉：team@idvb.dev", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun StatusBanner(message: String, actions: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Info, null, Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(end = 8.dp))
            }
            Row(Modifier.align(Alignment.End), content = actions)
        }
    }
}

@Composable
private fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    transformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    onIme: () -> Unit,
) {
    OutlinedTextField(
        value, onChange, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = trailing,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = transformation,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions { onIme() },
    )
}

@Composable
private fun EmailField(
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    imeAction: ImeAction = ImeAction.Next,
    supporting: String? = null,
    onIme: () -> Unit,
) = TextInput(value, { onChange(it.trim()) }, "邮箱", Icons.Outlined.Email, enabled, supporting,
    KeyboardType.Email, imeAction, onIme = onIme)

@Composable
private fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    imeAction: ImeAction,
    supporting: String? = null,
    onIme: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    TextInput(value, onChange, label, Icons.Outlined.Lock, enabled, supporting, KeyboardType.Password, imeAction,
        transformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "隐藏密码" else "显示密码")
            }
        }, onIme = onIme)
}
