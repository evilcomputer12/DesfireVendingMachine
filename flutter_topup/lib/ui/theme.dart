/// Dark Material 3 theme matching the vending kiosk's palette.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Kiosk palette.
class KioskColors {
  const KioskColors._();

  /// App background.
  static const Color background = Color(0xFF121212);

  /// Raised surfaces: cards, sheets, dialogs.
  static const Color surface = Color(0xFF1E1E1E);

  /// Surface one step above [surface], for nested containers.
  static const Color surfaceHigh = Color(0xFF262626);

  /// Primary accent.
  static const Color primary = Color(0xFFBB86FC);

  /// Secondary accent, used for confirmed money values.
  static const Color secondary = Color(0xFF03DAC6);

  /// Error accent.
  static const Color error = Color(0xFFCF6679);

  /// Primary text.
  static const Color onSurface = Color(0xFFE8E4EE);

  /// Muted text.
  static const Color onSurfaceMuted = Color(0xFF9A93A6);

  /// Hairline dividers and outlines.
  static const Color outline = Color(0xFF34303C);
}

/// Builds the app's dark theme.
ThemeData buildKioskTheme() {
  const scheme = ColorScheme.dark(
    primary: KioskColors.primary,
    onPrimary: Color(0xFF1B0033),
    primaryContainer: Color(0xFF3A2B52),
    onPrimaryContainer: Color(0xFFEBDCFF),
    secondary: KioskColors.secondary,
    onSecondary: Color(0xFF00201C),
    secondaryContainer: Color(0xFF10403C),
    onSecondaryContainer: Color(0xFFB8F5EE),
    error: KioskColors.error,
    onError: Color(0xFF370A15),
    errorContainer: Color(0xFF4A1F2A),
    onErrorContainer: Color(0xFFFFDAE0),
    surface: KioskColors.surface,
    onSurface: KioskColors.onSurface,
    onSurfaceVariant: KioskColors.onSurfaceMuted,
    surfaceContainerHighest: KioskColors.surfaceHigh,
    outline: KioskColors.outline,
    outlineVariant: Color(0xFF2A2730),
  );

  final base = ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    colorScheme: scheme,
    scaffoldBackgroundColor: KioskColors.background,
    canvasColor: KioskColors.background,
  );

  return base.copyWith(
    appBarTheme: const AppBarTheme(
      backgroundColor: KioskColors.background,
      surfaceTintColor: Colors.transparent,
      foregroundColor: KioskColors.onSurface,
      elevation: 0,
      centerTitle: false,
      systemOverlayStyle: SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.light,
      ),
      titleTextStyle: TextStyle(
        fontSize: 20,
        fontWeight: FontWeight.w600,
        letterSpacing: 0.1,
        color: KioskColors.onSurface,
      ),
    ),
    cardTheme: CardThemeData(
      color: KioskColors.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(24),
        side: const BorderSide(color: KioskColors.outline),
      ),
    ),
    bottomSheetTheme: const BottomSheetThemeData(
      backgroundColor: KioskColors.surface,
      surfaceTintColor: Colors.transparent,
      showDragHandle: true,
      dragHandleColor: KioskColors.outline,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size.fromHeight(60),
        textStyle: const TextStyle(
          fontSize: 17,
          fontWeight: FontWeight.w600,
          letterSpacing: 0.2,
        ),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size.fromHeight(56),
        foregroundColor: KioskColors.onSurface,
        side: const BorderSide(color: KioskColors.outline),
        textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(foregroundColor: KioskColors.primary),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: KioskColors.surfaceHigh,
      contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 22),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(18),
        borderSide: const BorderSide(color: KioskColors.outline),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(18),
        borderSide: const BorderSide(color: KioskColors.outline),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(18),
        borderSide: const BorderSide(color: KioskColors.primary, width: 2),
      ),
      errorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(18),
        borderSide: const BorderSide(color: KioskColors.error),
      ),
      focusedErrorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(18),
        borderSide: const BorderSide(color: KioskColors.error, width: 2),
      ),
    ),
    chipTheme: ChipThemeData(
      backgroundColor: KioskColors.surfaceHigh,
      selectedColor: KioskColors.primary,
      side: const BorderSide(color: KioskColors.outline),
      labelStyle: const TextStyle(
        fontSize: 18,
        fontWeight: FontWeight.w700,
        color: KioskColors.onSurface,
      ),
      secondaryLabelStyle: const TextStyle(
        fontSize: 18,
        fontWeight: FontWeight.w700,
        color: Color(0xFF1B0033),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
    ),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: KioskColors.surfaceHigh,
      contentTextStyle: const TextStyle(color: KioskColors.onSurface),
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
    ),
    dividerTheme: const DividerThemeData(
      color: KioskColors.outline,
      thickness: 1,
      space: 1,
    ),
    textTheme: base.textTheme.apply(
      bodyColor: KioskColors.onSurface,
      displayColor: KioskColors.onSurface,
    ),
  );
}
