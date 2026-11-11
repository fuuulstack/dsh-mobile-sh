package com.dsh.mobile.v2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.v2.R
import com.dsh.mobile.v2.data.Model

/**
 * 待答交互卡片：审批（工具越权）与提问（一次性多题）。
 *
 * 视觉保持克制：细描边 + 低饱和底，靠图标和文案表意，不铺大面积高饱和色块。
 * 提交后由宿主推 cancel 帧撤卡，这里不乐观删除。
 */

/** 审批卡片：允许一次 / 拒绝。 */
@Composable
fun ApprovalCard(
    approval: Model.Approval,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ds.primarySoft, RoundedCornerShape(16.dp))
            .border(1.dp, Ds.primarySoftBorder, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_shield_alert),
                contentDescription = null,
                tint = Ds.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "审批请求",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Ds.primary,
                letterSpacing = 0.3.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            approval.headline(),
            fontSize = 15.sp,
            color = Ds.textPrimary,
            lineHeight = 21.sp,
        )
        val reason = approval.displayReason.ifBlank { approval.reason }
        if (reason.isNotBlank() && reason != approval.headline()) {
            Spacer(Modifier.height(6.dp))
            Text(reason, fontSize = 13.sp, color = Ds.textSecondary, lineHeight = 19.sp)
        }
        if (!approval.answerable) {
            Spacer(Modifier.height(10.dp))
            // 灰按钮比「点了没反应」诚实：这张卡是从历史记录重建的，宿主那边
            // 已经没有这条挂起的 waterfall 了，答回去只会石沉大海。
            Text(
                "这条审批来自历史记录，需要在电脑端重新发起才能答复",
                fontSize = 12.sp,
                color = Ds.textMuted,
                lineHeight = 17.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // 拒绝：描边款；允许：实心渐变。一屏一个主行动点。
            Box(modifier = Modifier.weight(1f)) {
                SecondaryButton(
                    label = "拒绝",
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    enabled = approval.answerable,
                ) { onDeny() }
            }
            Box(modifier = Modifier.weight(1f)) {
                PrimaryButton(
                    label = "允许一次",
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    enabled = approval.answerable,
                ) { onAllow() }
            }
        }
    }
}

/**
 * 提问卡片：一批问题一次提交。
 *
 * 草稿用 Compose 不可变 map 持有（Question.selected 是可变字段，就地改不触发重组）；
 * 提交时再写回 Question 实例，交给网关组 answers 数组。
 */
@Composable
fun QuestionCardView(
    card: Model.QuestionCard,
    onSubmit: () -> Unit,
) {
    // 初值读实例上已有的草稿：提交时会把选择写回 Question，回答失败回滚、或宿主
    // 重投把卡片重新画出来时，remember 重建也能把用户填过的东西带回来，不用重填。
    var selected by remember(card.eventId) {
        mutableStateOf(card.questions.associate { it.id to it.selected.toList() })
    }
    var custom by remember(card.eventId) {
        mutableStateOf(card.questions.associate { it.id to it.custom })
    }
    val complete = card.questions.all { q ->
        (selected[q.id]?.isNotEmpty() == true) || (custom[q.id]?.isNotBlank() == true)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ds.primarySoft, RoundedCornerShape(16.dp))
            .border(1.dp, Ds.primarySoftBorder, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_message_circle),
                contentDescription = null,
                tint = Ds.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "提问",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Ds.primary,
                letterSpacing = 0.3.sp,
            )
        }
        card.questions.forEach { q ->
            QuestionBlock(
                question = q,
                selectedLabels = selected[q.id] ?: emptyList(),
                customText = custom[q.id] ?: "",
                onToggle = { label ->
                    val cur = selected[q.id] ?: emptyList()
                    val next = if (q.multiSelect) {
                        if (cur.contains(label)) cur - label else cur + label
                    } else {
                        if (cur.contains(label)) emptyList() else listOf(label)
                    }
                    selected = selected + (q.id to next)
                },
                onCustomChange = { text ->
                    custom = custom + (q.id to text)
                },
            )
        }
        PrimaryButton(
            label = "提交回答",
            enabled = complete,
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) {
            // 写回实例：网关从 card.questions 组 answers。
            card.questions.forEach { q ->
                q.selected.clear()
                q.selected.addAll(selected[q.id] ?: emptyList())
                q.custom = custom[q.id] ?: ""
            }
            onSubmit()
        }
    }
}

@Composable
private fun QuestionBlock(
    question: Model.Question,
    selectedLabels: List<String>,
    customText: String,
    onToggle: (String) -> Unit,
    onCustomChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (question.header.isNotBlank()) {
            Text(
                question.header,
                fontSize = 11.sp,
                color = Ds.textTertiary,
                letterSpacing = 0.4.sp,
            )
        }
        Text(
            question.question,
            fontSize = 15.sp,
            color = Ds.textPrimary,
            lineHeight = 21.sp,
        )
        if (question.detail.isNotBlank()) {
            Text(
                question.detail,
                fontSize = 13.sp,
                color = Ds.textSecondary,
                lineHeight = 18.sp,
            )
        }
        question.options.forEach { option ->
            val checked = selectedLabels.contains(option.label)
            val shape = if (question.multiSelect) RoundedCornerShape(4.dp) else RoundedCornerShape(50)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (checked) Ds.bgSurface else Color.Transparent,
                        RoundedCornerShape(10.dp),
                    )
                    .border(
                        1.dp,
                        if (checked) Ds.primary else Ds.borderStrong,
                        RoundedCornerShape(10.dp),
                    )
                    .dsClickable { onToggle(option.label) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 选中才填主色（单选圆圈 / 多选方框），克制标记。
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .border(
                            1.5.dp,
                            if (checked) Ds.primary else Ds.textHint,
                            shape,
                        )
                        .background(if (checked) Ds.primary else Color.Transparent, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (checked) {
                        Icon(
                            painter = painterResource(R.drawable.ic_lucide_check),
                            contentDescription = null,
                            tint = Ds.onPrimary,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(option.label, fontSize = 14.sp, color = Ds.textPrimary)
                    if (option.description.isNotBlank()) {
                        Text(
                            option.description,
                            fontSize = 12.sp,
                            color = Ds.textTertiary,
                        )
                    }
                }
            }
        }
        // 「其他」自定义回答。
        BasicTextField(
            value = customText,
            onValueChange = onCustomChange,
            singleLine = false,
            textStyle = TextStyle(fontSize = 14.sp, color = Ds.textPrimary),
            cursorBrush = SolidColor(Ds.primary),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Ds.bgSurfaceVariant, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                ) {
                    if (customText.isEmpty()) {
                        Text("其它（自己填）", fontSize = 14.sp, color = Ds.textHint)
                    }
                    inner()
                }
            },
        )
    }
}
