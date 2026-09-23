/**
 * Screen capture backends.
 * <p>
 * {@code mediaprojection/} holds the MediaProjection-based backend. The app has a second
 * backend, the accessibility screenshot path in {@code InputService.takeScreenShots()} in
 * the root package. It lives there because {@code takeScreenshot()} may only be called by
 * an {@code AccessibilityService}, and {@code InputService}'s component name is pinned to
 * the root package by {@code Settings.Secure.enabled_accessibility_services}, so the
 * accessibility path cannot be moved into this package.
 * <p>
 * The two backends are peers rather than a primary and a helper: {@code MainService} picks
 * between them at startup depending on {@code MainService.EXTRA_FALLBACK_SCREEN_CAPTURE}.
 */
package net.christianbeier.droidvnc_ng.capture;
