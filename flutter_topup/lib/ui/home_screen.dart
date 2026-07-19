/// Home: read the card balance, then go to top-up.
library;

import 'package:flutter/material.dart';

import '../desfire/desfire_card.dart';
import '../desfire/value_file.dart';
import '../nfc/card_gateway.dart';
import '../nfc/nfc_service.dart';
import 'scan_sheet.dart';
import 'theme.dart';
import 'topup_screen.dart';
import 'widgets/balance_display.dart';

/// Landing screen of the top-up app.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.gateway});

  /// Card access.
  final CardGateway gateway;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  CardSnapshot? _snapshot;
  NfcAvailability? _availability;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _refreshAvailability();
  }

  Future<void> _refreshAvailability() async {
    try {
      final availability = await widget.gateway.checkAvailability();
      if (!mounted) return;
      setState(() => _availability = availability);
    } on Exception {
      if (!mounted) return;
      setState(() => _availability = NfcAvailability.unsupported);
    }
  }

  Future<void> _readBalance() async {
    setState(() => _busy = true);
    final snapshot = await showScanSheet<CardSnapshot>(
      context: context,
      title: 'Read balance',
      prompt: 'Hold the card flat against the back of the phone.',
      operation: () => widget.gateway.readBalance(identify: true),
      successMessage: (s) => '€${formatCents(s.balanceCents)}',
      onCancel: widget.gateway.cancel,
    );
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (snapshot != null) _snapshot = snapshot;
    });
  }

  Future<void> _openTopUp() async {
    final result = await Navigator.of(context).push<TopUpResult>(
      MaterialPageRoute(
        builder: (_) => TopUpScreen(
          gateway: widget.gateway,
          currentBalanceCents: _snapshot?.balanceCents,
        ),
      ),
    );
    if (!mounted || result == null) return;
    setState(() {
      _snapshot = CardSnapshot(
        balanceCents: result.newBalance,
        uid: _snapshot?.uid,
        product: _snapshot?.product,
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final nfcReady = _availability == NfcAvailability.enabled;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Card top-up'),
        actions: [
          IconButton(
            tooltip: 'Refresh NFC status',
            onPressed: _refreshAvailability,
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
          children: [
            if (_availability != null && !nfcReady) ...[
              _NfcWarning(availability: _availability!),
              const SizedBox(height: 20),
            ],
            BalanceCard(
              balanceCents: _snapshot?.balanceCents,
              uidHex: _snapshot?.uidHex,
              product: _snapshot?.product,
              stale: _busy,
            ),
            const SizedBox(height: 24),
            FilledButton.icon(
              onPressed: nfcReady && !_busy ? _readBalance : null,
              icon: const Icon(Icons.contactless_rounded),
              label: Text(
                _snapshot == null ? 'Tap card to read balance' : 'Read again',
              ),
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: nfcReady && !_busy ? _openTopUp : null,
              icon: const Icon(Icons.add_card_rounded),
              label: const Text('Top up'),
            ),
            const SizedBox(height: 32),
            const _BenchKeyBanner(),
            const SizedBox(height: 16),
            Text(
              'MIFARE DESFire EV2/EV3 · AES-128 · AuthenticateEV2First (0x71) '
              '· value file in CommMode.Full',
              textAlign: TextAlign.center,
              style: theme.textTheme.bodySmall?.copyWith(
                color: KioskColors.onSurfaceMuted,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _NfcWarning extends StatelessWidget {
  const _NfcWarning({required this.availability});

  final NfcAvailability availability;

  @override
  Widget build(BuildContext context) {
    final unsupported = availability == NfcAvailability.unsupported;
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: KioskColors.error.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: KioskColors.error.withValues(alpha: 0.4)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.nfc_rounded, color: KioskColors.error, size: 22),
          const SizedBox(width: 14),
          Expanded(
            child: Text(
              unsupported
                  ? 'This device has no NFC hardware, so cards cannot be read.'
                  : 'NFC is switched off. Turn it on in Android settings, then '
                        'press refresh.',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: KioskColors.onSurface,
                height: 1.4,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _BenchKeyBanner extends StatelessWidget {
  const _BenchKeyBanner();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: KioskColors.surfaceHigh,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: KioskColors.outline),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(
            Icons.warning_amber_rounded,
            color: KioskColors.primary,
            size: 22,
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Bench build',
                  style: Theme.of(
                    context,
                  ).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                ),
                const SizedBox(height: 4),
                Text(
                  'This build carries fixed test keys inside the APK. Never '
                  'ship it against cards that hold real value.',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: KioskColors.onSurfaceMuted,
                    height: 1.45,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
