package com.dsh.mobile.v2

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.dsh.mobile.v2.ui.AppRoot
import com.dsh.mobile.v2.ui.AppViewModel
import com.dsh.mobile.v2.ui.DshTheme

/**
 * v2 入口：Compose 根导航 + dsh://pair 深链转发。
 */
class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 截图限制已取消（2026-10-29 用户要求）：真机上也能正常截屏/录屏取证。
        // 原先生产构建会置 FLAG_SECURE，导致 screencap 全黑、真机验收只能靠模拟器。
        intent?.data?.let { vm.handlePairLink(it) }
        vm.requestNotificationPermission = {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001,
                )
            }
        }
        // 扫码页相机权限（进入扫码时申请；拒绝后扫码页给出失败文案，不反复弹）
        vm.requestCameraPermission = {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 1002)
            }
        }
        setContent {
            DshTheme { AppRoot(vm) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 相机：扫码页在权限弹窗关掉后要能自己恢复画面，否则用户要手点「重试」。
        if (requestCode == 1002) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            vm.onCameraPermissionResult(granted)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { vm.handlePairLink(it) }
    }

    override fun onResume() {
        super.onResume()
        // 从系统「安装未知应用」设置页返回：权限可能刚被打开，重绘版本卡片，
        // 否则界面会一直停在「去开启安装权限」（v1 同样处理）。
        vm.refreshInstallPermission()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 部分 ROM 从设置页返回只触发焦点变化，不重走 onResume。
        if (hasFocus) vm.refreshInstallPermission()
    }
}
