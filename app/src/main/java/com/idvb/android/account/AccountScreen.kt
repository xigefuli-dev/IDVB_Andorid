package com.idvb.android.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AccountScreen(accounts: AccountAccess, onExit: () -> Unit) {
    val state by accounts.state.collectAsState()
    val scope = rememberCoroutineScope()
    // Secrets and unfinished email verification are deliberately not saved in Activity state.
    var mode by remember { mutableStateOf("login") }
    var email by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("qq.com") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var verification by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var cooldown by remember { mutableIntStateOf(0) }
    LaunchedEffect(cooldown) { if (cooldown > 0) { kotlinx.coroutines.delay(1_000); cooldown-- } }
    BackHandler(onBack = onExit)

    fun submit(operation: String) {
        if (busy) return
        val address = if (mode == "register") "${email.trim()}@$domain" else email.trim()
        if (!address.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) {
            notice = "请填写有效的邮箱地址。"; return
        }
        if (operation.endsWith("confirm") && !code.matches(Regex("[0-9]{6}"))) {
            notice = "请输入邮件中的 6 位验证码。"; return
        }
        if (operation in listOf("register.confirm", "password_reset.confirm") &&
            (password.length !in 10..128 || password != confirmPassword)) {
            notice = "密码需为 10–128 位，且两次输入一致。"; return
        }
        if (operation == "register.confirm" && name.trim().length !in 2..32) {
            notice = "昵称需要 2–32 个字符。"; return
        }
        if (operation.startsWith("login") && password.isEmpty()) { notice = "请输入密码。"; return }
        val fields = buildMap {
            put("email", address)
            if (operation.startsWith("login") || operation.endsWith("confirm")) put("password", password)
            if (operation.endsWith("confirm")) put("code", code)
            if (operation == "register.confirm") put("displayName", name.trim())
        }
        busy = true
        notice = ""
        scope.launch {
            try {
                val result = accounts.authenticate(operation, fields)
                when (result) {
                    "verification_required", "code_sent" -> {
                        verification = true
                        cooldown = 60
                        notice = "验证码已发送；请查收邮箱（也请检查垃圾邮件）。"
                    }
                    else -> {
                        notice = result
                        password = ""; confirmPassword = ""; code = ""
                        if (operation == "password_reset.confirm") { mode = "login"; verification = false }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                notice = if (error is AccountFailure) error.message.orEmpty()
                    else "操作未完成，请检查网络、系统时间后重试。"
            } finally { busy = false }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(20.dp))
            Text("IDVB 账户", style = MaterialTheme.typography.headlineLarge)
            Text("登录有效账户后才能使用。手机与 PC 共用账户；封禁期间两端均不可用，解封后请重新登录。",
                style = MaterialTheme.typography.bodyMedium)
            Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.hasSession) {
                Button(onClick = accounts::refresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("重新验证账户") }
                TextButton(onClick = accounts::logout, enabled = !busy) { Text("退出当前账户") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("login" to "登录", "register" to "注册", "password_reset" to "找回密码").forEach { (value, label) ->
                    FilterChip(selected = mode == value, enabled = !busy, onClick = {
                        mode = value; verification = false; code = ""; password = ""; confirmPassword = ""; notice = ""
                    }, label = { Text(label) })
                }
            }
            OutlinedTextField(email, { email = it; verification = false; code = "" },
                enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (mode == "register") "邮箱用户名（不含 @）" else "邮箱") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            if (mode == "register") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("qq.com", "gmail.com").forEach { suffix ->
                        FilterChip(selected = domain == suffix, enabled = !busy,
                            onClick = { domain = suffix; verification = false; code = "" }, label = { Text("@$suffix") })
                    }
                }
                OutlinedTextField(name, { name = it }, enabled = !busy, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text("昵称") })
            }
            if (mode == "login" || verification) {
                OutlinedTextField(password, { password = it }, enabled = !busy, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text(if (mode == "password_reset") "新密码" else "密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (mode != "login") OutlinedTextField(confirmPassword, { confirmPassword = it }, enabled = !busy,
                    singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("再次输入密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
            if (verification) {
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, enabled = !busy, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text("邮箱验证码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                TextButton(enabled = !busy && cooldown == 0, onClick = {
                    submit(when (mode) { "register" -> "register.start"; "login" -> "login"; else -> "password_reset.request" })
                }) { Text(if (cooldown > 0) "${cooldown} 秒后可重新发送" else "重新发送验证码") }
            }
            if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                submit(if (verification) "$mode.confirm" else when (mode) {
                    "register" -> "register.start"; "password_reset" -> "password_reset.request"; else -> "login"
                })
            }) { Text(if (verification) "确认并继续" else if (mode == "login") "登录" else "发送验证码") }
            Text("账户申诉：team@idvb.dev", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onExit) { Text("退出应用") }
        }
    }
}
