/// Transaction result: old balance -> new balance.
library;

import 'package:flutter/material.dart';

import '../desfire/desfire_card.dart';
import '../desfire/value_file.dart';
import 'theme.dart';
import 'widgets/balance_display.dart';

/// Full-screen confirmation of a committed value-file change.
class ResultScreen extends StatelessWidget {
  const ResultScreen({super.key, required this.result});

  /// The committed result, read back from the card after
  /// `CommitTransaction`.
  final TopUpResult result;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final credited = result.amount >= 0;
    final accent = credited ? KioskColors.secondary : KioskColors.primary;

    return Scaffold(
      appBar: AppBar(
        automaticallyImplyLeading: false,
        title: Text(credited ? 'Top-up complete' : 'Payment complete'),
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
          child: Column(
            children: [
              const SizedBox(height: 8),
              Container(
                width: 92,
                height: 92,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: accent.withValues(alpha: 0.14),
                  border: Border.all(color: accent.withValues(alpha: 0.5)),
                ),
                child: Icon(Icons.check_rounded, size: 46, color: accent),
              ),
              const SizedBox(height: 22),
              Text(
                credited
                    ? '€${formatCents(result.amount)} added'
                    : '€${formatCents(result.amount.abs())} charged',
                style: theme.textTheme.headlineSmall?.copyWith(
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                'Committed to the card',
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: KioskColors.onSurfaceMuted,
                ),
              ),
              const SizedBox(height: 30),
              Card(
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 22,
                    vertical: 26,
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: _BalanceColumn(
                          label: 'BEFORE',
                          cents: result.previousBalance,
                          color: KioskColors.onSurfaceMuted,
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 6),
                        child: Icon(
                          Icons.arrow_forward_rounded,
                          color: accent,
                          size: 28,
                        ),
                      ),
                      Expanded(
                        child: _BalanceColumn(
                          label: 'AFTER',
                          cents: result.newBalance,
                          color: accent,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              const Spacer(),
              FilledButton(
                onPressed: () => Navigator.of(context).pop(),
                child: const Text('Done'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _BalanceColumn extends StatelessWidget {
  const _BalanceColumn({
    required this.label,
    required this.cents,
    required this.color,
  });

  final String label;
  final int cents;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: Theme.of(context).textTheme.labelSmall?.copyWith(
            letterSpacing: 1.4,
            fontWeight: FontWeight.w700,
            color: KioskColors.onSurfaceMuted,
          ),
        ),
        const SizedBox(height: 8),
        MoneyText(cents, size: 34, color: color),
      ],
    );
  }
}
