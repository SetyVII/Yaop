package org.yaop.ui.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.core.model.PkmObject
import org.yaop.core.model.PropertyValue
import org.yaop.core.repository.PkmRepository
import org.yaop.ui.theme.AppFonts
import org.yaop.ui.theme.AppColors
import kotlinx.coroutines.launch

/** "Por revisar": objects whose `reviewed` property is explicitly false (shares, captures). */
fun inboxOf(all: List<PkmObject>): List<PkmObject> =
    all.filter { (it.property("reviewed") as? PropertyValue.Checkbox)?.checked == false && !it.isTemplate }

@Composable
fun InboxScreen(all: List<PkmObject>, repository: PkmRepository, onOpen: (String) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val items = inboxOf(all)
    Column(Modifier.fillMaxSize().background(org.yaop.ui.theme.AppBackgroundBrush.Background).padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.inbox_title), Modifier.weight(1f), fontFamily = AppFonts.InterTight, fontSize = 30.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
            Text(stringResource(R.string.common_close), color = AppColors.Ice, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).padding(8.dp))
        }
        if (items.isEmpty()) Text(stringResource(R.string.inbox_empty), color = AppColors.TextSecondary, modifier = Modifier.padding(top = 16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items, key = { it.id }) { o ->
                val shape = RoundedCornerShape(14.dp)
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(AppColors.Surface, shape).clickable { onOpen(o.id) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(o.plainTitle, Modifier.weight(1f), fontSize = 15.sp, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.inbox_mark), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Ice,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            scope.launch { repository.saveObject(o.copy(properties = o.properties + ("reviewed" to PropertyValue.Checkbox(true)))) }
                        }.padding(8.dp),
                    )
                }
            }
        }
    }
}
