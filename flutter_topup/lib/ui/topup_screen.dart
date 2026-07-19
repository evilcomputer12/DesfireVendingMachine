/// Top-up amount selection: preset chips plus a custom amount entry.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../app_config.dart';
import '../desfire/desfire_card.dart';
import '../desfire/value_file.dart';
import '../nfc/card_gateway.dart';
import 'result_screen.dart';
import 'scan_sheet.dart';
import 'theme.dart';
import 'widgets/balance_display.dart';

/// Lets the user pick an amount and then tap the card to credit it.
class TopUpScreen extends StatefulWidget {
  const TopUpScreen({
    super.key,
    required this.gateway,
    this.currentBalanceCents,
  });

  /// Card access.
  final CardGateway gateway;

  /// Last known balance, used to preview the resulting balance.
  final int? currentBalanceCents;

  @override
  State<TopUpScreen> createState() => _TopUpScreenState();
}

class _TopUpScreenState extends State<TopUpScreen> {
  final TextEditingController _customController = TextEditingController();
  final FocusNode _customFocus = FocusNode();

  int? _selectedPreset = AppConfig.presetAmountsCents.first;
  int? _customCents;
  String? _customError;

  int? get _amountCents => _selectedPreset ?? _customCents;

  @override
  void dispose() {
    _customController.dispose();
    _customFocus.dispose();
    super.dispose();
  }

  void _selectPreset(int cents) {
    setState(() {
      _selectedPreset = cents;
      _customCents = null;
      _customError = null;
      _customController.clear();
    });
    _customFocus.unfocus();
  }

  void _onCustomChanged(String value) {
    setState(() {
      _selectedPreset = null;
      if (value.trim().isEmpty) {
        _customCents = null;
        _customError = null;
        return;
      }
      final cents = parseEurosToCents(value);
      if (cents == null) {
        _customCents = null;
        _customError = 'Enter an amount like 7.50';
      } else if (cents == 0) {
        _customCents = null;
        _customError = 'Amount must be more than zero';
      } else if (cents > AppConfig.upperLimitCents) {
        _customCents = null;
        _customError =
            'Maximum card balance is €${formatCents(AppConfig.upperLimitCents)}';
      } else {
        _customCents = cents;
        _customError = null;
      }
    });
  }

  Future<void> _confirm() async {
    final amount = _amountCents;
    if (amount == null) return;
    _customFocus.unfocus();

    final result = await showScanSheet<TopUpResult>(
      context: context,
      title: 'Top up €${formatCents(amount)}',
      prompt:
          'The balance changes only after the card confirms the transaction. '
          'Keep it against the phone until you see the tick.',
      operation: () => widget.gateway.topUp(amount),
      successMessage: (r) => 'Balance €${formatCents(r.newBalance)}',
      onCancel: widget.gateway.cancel,
    );

    if (!mounted || result == null) return;
    await Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => ResultScreen(result: result)),
    );
    if (!mounted) return;
    Navigator.of(context).pop(result);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final amount = _amountCents;
    final current = widget.currentBalanceCents;

    return Scaffold(
      appBar: AppBar(title: const Text('Top up')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
          children: [
            Text(
              'CHOOSE AN AMOUNT',
              style: theme.textTheme.labelMedium?.copyWith(
                letterSpacing: 1.6,
                fontWeight: FontWeight.w700,
                color: KioskColors.onSurfaceMuted,
              ),
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                for (final preset in AppConfig.presetAmountsCents) ...[
                  Expanded(
                    child: _AmountChip(
                      label: '€${formatCents(preset)}',
                      selected: _selectedPreset == preset,
                      onTap: () => _selectPreset(preset),
                    ),
                  ),
                  if (preset != AppConfig.presetAmountsCents.last)
                    const SizedBox(width: 12),
                ],
              ],
            ),
            const SizedBox(height: 28),
            Text(
              'OR ENTER YOUR OWN',
              style: theme.textTheme.labelMedium?.copyWith(
                letterSpacing: 1.6,
                fontWeight: FontWeight.w700,
                color: KioskColors.onSurfaceMuted,
              ),
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _customController,
              focusNode: _customFocus,
              onChanged: _onCustomChanged,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
              ),
              inputFormatters: [
                FilteringTextInputFormatter.allow(RegExp(r'[0-9.,]')),
                LengthLimitingTextInputFormatter(8),
              ],
              style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w700),
              decoration: InputDecoration(
                prefixIcon: const Padding(
                  padding: EdgeInsets.only(left: 20, right: 8),
                  child: Text(
                    '€',
                    style: TextStyle(
                      fontSize: 26,
                      fontWeight: FontWeight.w600,
                      color: KioskColors.onSurfaceMuted,
                    ),
                  ),
                ),
                prefixIconConstraints: const BoxConstraints(minWidth: 0),
                hintText: '0.00',
                errorText: _customError,
              ),
            ),
            const SizedBox(height: 28),
            if (amount != null && current != null)
              Card(
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 22,
                    vertical: 20,
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              'New balance',
                              style: theme.textTheme.bodyMedium?.copyWith(
                                color: KioskColors.onSurfaceMuted,
                              ),
                            ),
                            const SizedBox(height: 6),
                            Text(
                              '€${formatCents(current)} → '
                              '€${formatCents(current + amount)}',
                              style: theme.textTheme.titleMedium?.copyWith(
                                fontWeight: FontWeight.w700,
                                color: KioskColors.secondary,
                              ),
                            ),
                          ],
                        ),
                      ),
                      MoneyText(
                        amount,
                        size: 26,
                        showSign: true,
                        color: KioskColors.secondary,
                      ),
                    ],
                  ),
                ),
              ),
            const SizedBox(height: 24),
            FilledButton.icon(
              onPressed: amount == null ? null : _confirm,
              icon: const Icon(Icons.contactless_rounded),
              label: Text(
                amount == null
                    ? 'Choose an amount'
                    : 'Tap card to add €${formatCents(amount)}',
              ),
            ),
            const SizedBox(height: 14),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(
                  Icons.info_outline_rounded,
                  size: 18,
                  color: KioskColors.onSurfaceMuted,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'Nothing is written until the card commits the '
                    'transaction. If you pull the card away mid-write, the '
                    'balance stays exactly as it was.',
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: KioskColors.onSurfaceMuted,
                      height: 1.45,
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _AmountChip extends StatelessWidget {
  const _AmountChip({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: selected ? KioskColors.primary : KioskColors.surfaceHigh,
      borderRadius: BorderRadius.circular(18),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(18),
        child: Container(
          height: 76,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(18),
            border: Border.all(
              color: selected ? KioskColors.primary : KioskColors.outline,
              width: selected ? 2 : 1,
            ),
          ),
          child: Text(
            label,
            style: TextStyle(
              fontSize: 20,
              fontWeight: FontWeight.w700,
              color: selected ? const Color(0xFF1B0033) : KioskColors.onSurface,
            ),
          ),
        ),
      ),
    );
  }
}
