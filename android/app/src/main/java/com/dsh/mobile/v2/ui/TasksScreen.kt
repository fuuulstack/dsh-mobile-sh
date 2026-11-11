package com.dsh.mobile.v2.ui

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
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.dsh.mobile.v2.R

/**
 * 后台任务页。
 *
 * 宿主还没有任务接口（v1 同样只有这一屏、没有数据源），所以这里不假装有列表：
 * 说清「现在没有、接口就绪后会显示什么」，并给一条回聊天页的路。
 * 入口本身必须有反应——点了什么都不发生是最难排查的一类问题。
 */
@Composable
fun TasksScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ds.bgPage),
    ) {
        // 顶栏与聊天页同构：左返回、居中标题，宽度固定不随标题长度跳动。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .align(Alignment.CenterStart)
                    .dsClickableNeutral { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_chevron_left),
                    contentDescription = "返回",
                    tint = Ds.textPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                "后台任务",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = Ds.textPrimary,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ds.bgSurface, RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_clock),
                        contentDescription = null,
                        tint = Ds.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "还没有正在运行的任务",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Ds.textPrimary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "宿主还没有提供任务接口，所以这里读不到进度、时间线和实时输出。",
                    fontSize = 13.sp,
                    color = Ds.textMuted,
                    lineHeight = 20.sp,
                )
            }

            Text(
                "接口就绪后这里会显示什么",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Ds.textSecondary,
            )
            listOf(
                "运行中的任务：状态、已运行时长、进度条",
                "执行时间线：每一步的开始与结束",
                "实时输出：任务自己打印的日志",
                "产物：构建出来的文件，可直接下载",
            ).forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .size(4.dp)
                            .background(Ds.textMuted, RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        line,
                        fontSize = 13.sp,
                        color = Ds.textMuted,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}
