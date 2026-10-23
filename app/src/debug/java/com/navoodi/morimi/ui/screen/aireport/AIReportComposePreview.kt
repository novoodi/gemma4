
package com.navoodi.morimi.ui.screen.aireport

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.navoodi.morimi.ui.theme.Gemma4Theme

@Preview(name = "장소", widthDp = 412, heightDp = 915, showBackground = true, locale = "ko")
@Composable
private fun PlacesPreview() = ReportPagePreview(0)

@Preview(name = "요약", widthDp = 412, heightDp = 915, showBackground = true, locale = "ko")
@Composable
private fun SummaryPreview() = ReportPagePreview(1)

@Preview(name = "날씨", widthDp = 412, heightDp = 915, showBackground = true, locale = "ko")
@Composable
private fun WeatherPreview() = ReportPagePreview(2)

@Composable
private fun ReportPagePreview(initialPage: Int) {
    Gemma4Theme {
        AIReportContent(
            summary = sampleSummary,
            isModelAvailable = true,
            roomId = "report-preview",
            events = emptyList(),
            isAddedToCalendar = false,
            onBack = {},
            onModelDownload = {},
            onTogglePlace = {},
            onVote = {},
            onAddToCalendar = {},
            onGoToCalendar = {},
            initialPage = initialPage,
        )
    }
}