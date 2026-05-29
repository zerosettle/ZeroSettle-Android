package com.zerosettle.sdk.offers
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BannerVisibilityTest {
    private val vp = BannerVisibility.Rect(0f, 0f, 1080f, 2400f)
    @Test fun fullyInside() {
        assertThat(BannerVisibility.visibleFraction(BannerVisibility.Rect(40f, 600f, 1040f, 900f), vp)).isWithin(0.001f).of(1f)
        assertThat(BannerVisibility.isOnScreen(BannerVisibility.Rect(40f, 600f, 1040f, 900f), vp)).isTrue()
    }
    @Test fun halfBelow() {
        assertThat(BannerVisibility.visibleFraction(BannerVisibility.Rect(40f, 2300f, 1040f, 2500f), vp)).isWithin(0.001f).of(0.5f)
    }
    @Test fun belowThreshold() {
        assertThat(BannerVisibility.isOnScreen(BannerVisibility.Rect(40f, 2340f, 1040f, 2540f), vp, 0.5f)).isFalse()
    }
    @Test fun fullyAbove() {
        assertThat(BannerVisibility.visibleFraction(BannerVisibility.Rect(40f, -300f, 1040f, -100f), vp)).isWithin(0.001f).of(0f)
    }
    @Test fun zeroHeight() {
        assertThat(BannerVisibility.visibleFraction(BannerVisibility.Rect(40f, 600f, 1040f, 600f), vp)).isWithin(0.001f).of(0f)
    }
}
