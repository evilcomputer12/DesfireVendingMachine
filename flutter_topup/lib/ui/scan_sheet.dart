/// Tap-to-scan bottom sheet with pulsing NFC animation and success/failure
/// states.
library;

import 'dart:async';

import 'package:flutter/material.dart';

import '../desfire/desfire_exceptions.dart';
import 'theme.dart';
import 'widgets/nfc_pulse.dart';

/// What the sheet is currently showing.
enum ScanPhase {
  /// Waiting for the card to enter the field.
  waiting,

  /// Card found, protocol exchange in flight — do not move the card.
  working,

  /// Operation completed.
  success,

  /// Operation failed.
  failure,
}

/// Shows a modal scan sheet, runs [operation], and returns its result.
///
/// Returns null when the user cancels or the operation fails. Failures are
/// rendered inside the sheet with a retry affordance, so the caller only sees
/// null once the user gives up.
///
/// [isTerminal] marks failures that retrying cannot fix, so the sheet closes
/// straight away and hands the error to [onTerminalError] instead of showing a
/// "Try again" button. The motivating case is
/// [CardNotProvisionedException]: tapping the same blank card again will
/// always fail, and what the user actually needs is the offer to set it up.
Future<T?> showScanSheet<T>({
  required BuildContext context,
  required String title,
  required String prompt,
  required Future<T> Function() operation,
  required String Function(T result) successMessage,
  Future<void> Function()? onCancel,
  bool Function(Object error)? isTerminal,
  void Function(Object error)? onTerminalError,
}) {
  return showModalBottomSheet<T>(
    context: context,
    isScrollControlled: true,
    isDismissible: false,
    enableDrag: false,
    backgroundColor: KioskColors.surface,
    builder: (context) => _ScanSheet<T>(
      title: title,
      prompt: prompt,
      operation: operation,
      successMessage: successMessage,
      onCancel: onCancel,
      isTerminal: isTerminal,
      onTerminalError: onTerminalError,
    ),
  );
}

class _ScanSheet<T> extends StatefulWidget {
  const _ScanSheet({
    required this.title,
    required this.prompt,
    required this.operation,
    required this.successMessage,
    this.onCancel,
    this.isTerminal,
    this.onTerminalError,
  });

  final String title;
  final String prompt;
  final Future<T> Function() operation;
  final String Function(T result) successMessage;
  final Future<void> Function()? onCancel;
  final bool Function(Object error)? isTerminal;
  final void Function(Object error)? onTerminalError;

  @override
  State<_ScanSheet<T>> createState() => _ScanSheetState<T>();
}

class _ScanSheetState<T> extends State<_ScanSheet<T>> {
  ScanPhase _phase = ScanPhase.waiting;
  String? _errorTitle;
  String? _errorDetail;
  T? _result;

  @override
  void initState() {
    super.initState();
    unawaited(_start());
  }

  Future<void> _start() async {
    setState(() {
      _phase = ScanPhase.waiting;
      _errorTitle = null;
      _errorDetail = null;
    });
    try {
      final value = await widget.operation();
      if (!mounted) return;
      setState(() {
        _result = value;
        _phase = ScanPhase.success;
      });
      await Future<void>.delayed(const Duration(milliseconds: 900));
      if (!mounted) return;
      Navigator.of(context).pop(value);
    } catch (error) {
      if (!mounted) return;
      if (widget.isTerminal?.call(error) ?? false) {
        widget.onTerminalError?.call(error);
        await widget.onCancel?.call();
        if (!mounted) return;
        Navigator.of(context).pop();
        return;
      }
      final described = _describe(error);
      setState(() {
        _phase = ScanPhase.failure;
        _errorTitle = described.$1;
        _errorDetail = described.$2;
      });
    }
  }

  /// Maps an exception onto a headline and an explanatory line.
  (String, String) _describe(Object error) {
    switch (error) {
      case InsufficientFundsException e:
        return ('Not enough balance', e.message);
      case LimitExceededException e:
        return ('Card limit reached', e.message);
      case CardNotProvisionedException e:
        return ('Card is not set up', e.message);
      case ProvisioningFailedException e:
        return ('Setup did not finish', e.message);
      case AuthenticationFailedException e:
        return ('Card not recognised', e.message);
      case CmacMismatchException e:
        return ('Secure channel failed', e.message);
      case CardStatusException e:
        return ('Card refused the operation', '${e.message} (${e.statusHex})');
      case TransceiveException e:
        return ('Card moved away', e.message);
      case NoCardException e:
        return ('No card detected', e.message);
      case DesfireException e:
        return ('Something went wrong', e.message);
      default:
        return ('Something went wrong', error.toString());
    }
  }

  Future<void> _cancel() async {
    await widget.onCancel?.call();
    if (!mounted) return;
    Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(24, 4, 24, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              widget.title,
              style: theme.textTheme.titleLarge?.copyWith(
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 28),
            SizedBox(height: 180, child: Center(child: _buildIndicator())),
            const SizedBox(height: 26),
            Text(
              _headline(),
              textAlign: TextAlign.center,
              style: theme.textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.w700,
                color: _accentColor(),
              ),
            ),
            const SizedBox(height: 8),
            SizedBox(
              height: 62,
              child: SingleChildScrollView(
                child: Text(
                  _detail(),
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: KioskColors.onSurfaceMuted,
                    height: 1.4,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 20),
            if (_phase == ScanPhase.failure) ...[
              FilledButton(onPressed: _start, child: const Text('Try again')),
              const SizedBox(height: 10),
              TextButton(onPressed: _cancel, child: const Text('Cancel')),
            ] else if (_phase == ScanPhase.waiting) ...[
              OutlinedButton(onPressed: _cancel, child: const Text('Cancel')),
            ] else
              const SizedBox(height: 56),
          ],
        ),
      ),
    );
  }

  Widget _buildIndicator() {
    switch (_phase) {
      case ScanPhase.waiting:
        return const NfcPulse(color: KioskColors.primary);
      case ScanPhase.working:
        return const NfcPulse(color: KioskColors.secondary);
      case ScanPhase.success:
        return _StatusBadge(
          icon: Icons.check_rounded,
          color: KioskColors.secondary,
        );
      case ScanPhase.failure:
        return _StatusBadge(
          icon: Icons.close_rounded,
          color: KioskColors.error,
        );
    }
  }

  Color _accentColor() => switch (_phase) {
    ScanPhase.success => KioskColors.secondary,
    ScanPhase.failure => KioskColors.error,
    _ => KioskColors.onSurface,
  };

  String _headline() => switch (_phase) {
    ScanPhase.waiting => 'Hold your card to the phone',
    ScanPhase.working => 'Keep the card still',
    ScanPhase.success =>
      _result == null ? 'Done' : widget.successMessage(_result as T),
    ScanPhase.failure => _errorTitle ?? 'Failed',
  };

  String _detail() => switch (_phase) {
    ScanPhase.waiting => widget.prompt,
    ScanPhase.working =>
      'Writing to the card. Moving it now cancels the transaction and nothing '
          'is charged.',
    ScanPhase.success => 'You can take the card away.',
    ScanPhase.failure => _errorDetail ?? '',
  };
}

class _StatusBadge extends StatelessWidget {
  const _StatusBadge({required this.icon, required this.color});

  final IconData icon;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0.7, end: 1),
      duration: const Duration(milliseconds: 260),
      curve: Curves.easeOutBack,
      builder: (context, scale, child) =>
          Transform.scale(scale: scale, child: child),
      child: Container(
        width: 116,
        height: 116,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: color.withValues(alpha: 0.14),
          border: Border.all(color: color.withValues(alpha: 0.55), width: 2),
        ),
        child: Icon(icon, size: 56, color: color),
      ),
    );
  }
}
