package com.georgernstgraf.polishedrecognition.service

import android.view.LayoutInflater
import android.view.View
import android.view.ContextThemeWrapper
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.georgernstgraf.polishedrecognition.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #157: the real IME layout under dispatched window insets.
 *
 * On API 35 the window is edge-to-edge (verified on an emulator: IME window
 * `[0,2058][1080,2400]`, navigation bar `[0,2274][1080,2400]`), so the bottom
 * row is covered by the system buttons unless the root takes the inset. This
 * test drives the same [ImeWindowInsets.apply] call the IME makes and fails if
 * the padding is dropped — or applied twice.
 */
@RunWith(RobolectricTestRunner::class)
class ImeInputViewInsetsTest {

    private fun root(): View {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            android.R.style.Theme_Material_Light
        )
        val view = LayoutInflater.from(context).inflate(R.layout.ime_voice_input, null)
        return view.findViewById(R.id.ime_root)
    }

    private fun insets(
        navigationBars: Insets = Insets.NONE,
        displayCutout: Insets = Insets.NONE,
    ): WindowInsetsCompat = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.navigationBars(), navigationBars)
        .setInsets(WindowInsetsCompat.Type.displayCutout(), displayCutout)
        .build()

    @Test
    fun `three button navigation bar moves the bottom row above the system buttons`() {
        val root = root()
        val base = ImeWindowInsets.baseOf(root)

        ImeWindowInsets.apply(root, base, insets(navigationBars = Insets.of(0, 0, 0, 126)))

        assertThat(root.paddingBottom).isEqualTo(base.bottom + 126)
        assertThat(root.paddingLeft).isEqualTo(base.left)
        assertThat(root.paddingRight).isEqualTo(base.right)
    }

    @Test
    fun `repeated inset dispatch does not grow the padding`() {
        val root = root()
        val base = ImeWindowInsets.baseOf(root)
        val dispatched = insets(navigationBars = Insets.of(0, 0, 0, 126))

        ImeWindowInsets.apply(root, base, dispatched)
        val once = root.paddingBottom
        ImeWindowInsets.apply(root, base, dispatched)
        ImeWindowInsets.apply(root, base, dispatched)

        assertThat(root.paddingBottom).isEqualTo(once)
        assertThat(once).isEqualTo(base.bottom + 126)
    }

    @Test
    fun `hiding the navigation bar restores the declared padding`() {
        val root = root()
        val base = ImeWindowInsets.baseOf(root)
        ImeWindowInsets.apply(root, base, insets(navigationBars = Insets.of(0, 0, 0, 126)))

        ImeWindowInsets.apply(root, base, insets())

        assertThat(root.paddingBottom).isEqualTo(base.bottom)
    }

    @Test
    fun `landscape cutout pads the sides`() {
        val root = root()
        val base = ImeWindowInsets.baseOf(root)

        ImeWindowInsets.apply(
            root, base,
            insets(
                navigationBars = Insets.of(0, 0, 126, 0),
                displayCutout = Insets.of(100, 0, 0, 0)
            )
        )

        assertThat(root.paddingLeft).isEqualTo(base.left + 100)
        assertThat(root.paddingRight).isEqualTo(base.right + 126)
    }

    @Test
    fun `the listener applies the same rule via dispatch`() {
        // Guards the wiring, not just the helper: the real listener path must
        // produce the inset-aware padding.
        val root = root()
        val base = ImeWindowInsets.baseOf(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            ImeWindowInsets.apply(view, base, windowInsets)
            windowInsets
        }

        ViewCompat.dispatchApplyWindowInsets(
            root, insets(navigationBars = Insets.of(0, 0, 0, 126))
        )

        assertThat(root.paddingBottom).isEqualTo(base.bottom + 126)
    }
}
