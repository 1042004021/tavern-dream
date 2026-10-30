package com.taverndream.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 页面结构：角色列表（主页） → 对话 / 设置（都是二级页面）。
 * 设置入口在主页右上角菜单，不再占用底部 Tab。
 */
@Composable
fun TavernApp(vm: com.taverndream.AppViewModel) {
    var showSettings by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importCard(uri) }

    LaunchedEffect(vm.toast) {
        vm.toast?.let {
            snackbar.showSnackbar(it)
            vm.toast = null
        }
    }

    val current = vm.current
    if (current != null) {
        ChatScreen(vm = vm, card = current, snackbar = snackbar)
        return
    }

    if (showSettings) {
        SettingsScreen(vm = vm, onBack = { showSettings = false })
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        CharacterListScreen(
            vm = vm,
            padding = padding,
            onImport = {
                picker.launch(arrayOf("image/png", "application/json", "application/zip", "*/*"))
            },
            onOpenSettings = { showSettings = true },
        )
    }
}