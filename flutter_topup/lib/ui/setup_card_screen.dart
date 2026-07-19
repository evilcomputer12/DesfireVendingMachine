/// "Set up this card" — the explicit, opt-in provisioning flow.
///
/// Provisioning writes a new application onto whatever card is on the reader,
/// and this app cannot undo that. So it is never automatic and never a side
/// effect of a scan: the user reaches this screen only after a read has come
/// back with [CardNotProvisionedException], has to press a button that says
/// what it does, and — unless `AppConfig.autoProvision` is set for a bench
/// session — has to confirm a dialog that names the consequences first. The
/// card on the reader may be an office badge or a transit card; those are
/// DESFire too, and look identical to a blank card until you try.
library;

import 'package:flutter/material.dart';

import '../app_config.dart';
import '../desfire/desfire_exceptions.dart';
import '../desfire/value_file.dart';
import '../nfc/card_gateway.dart';
import 'theme.dart';
import 'widgets/nfc_pulse.dart';

/// Where the setup flow currently is.
enum SetupPhase {
  /// Explaining what will happen; nothing has been written.
  explaining,

  /// Waiting for the card and running the provisioning steps.
  running,

  /// The card is provisioned and was read back successfully.
  done,

  /// Something failed; [SetupCardScreen] shows which step.
  failed,
}

/// Full-screen provisioning flow. Pops with the resulting [CardSnapshot] on
/// success, or null if the user backs out.
class SetupCardScreen extends StatefulWidget {
  const SetupCardScreen({super.key, required this.gateway});

  /// Card access.
  final CardGateway gateway;

  @override
  State<SetupCardScreen> createState() => _SetupCardScreenState();
}

class _SetupCardScreenState extends State<SetupCardScreen> {
  SetupPhase _phase = SetupPhase.explaining;
  ProvisioningStep? _currentStep;
  final Set<ProvisioningStep> _completed = {};
  CardSnapshot? _result;
  String? _errorTitle;
  String? _errorDetail;

  /// Steps shown in the checklist, in the order `provisionCard` runs them.
  static const List<ProvisioningStep> _visibleSteps = [
    ProvisioningStep.probe,
    ProvisioningStep.authenticatePicc,
    ProvisioningStep.createApplication,
    ProvisioningStep.changeUserKey,
    ProvisioningStep.changeMasterKey,
    ProvisioningStep.createValueFile,
    ProvisioningStep.verify,
  ];

  Future<void> _confirmAndRun() async {
    if (!AppConfig.autoProvision) {
      final confirmed = await _confirm();
      if (confirmed != true) return;
    }
    if (!mounted) return;
    await _run();
  }

  Future<bool?> _confirm() {
    return showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: KioskColors.surfaceHigh,
        title: const Text('Set up this card?'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'A new application (AID '
              '0x${AppConfig.applicationId.toRadixString(16).padLeft(6, '0').toUpperCase()}) '
              'will be created on the card that is on the reader, with its own '
              'keys and a balance file starting at €0.00.',
              style: Theme.of(
                context,
              ).textTheme.bodyMedium?.copyWith(height: 1.45),
            ),
            const SizedBox(height: 14),
            Text(
              'This app cannot undo it. Nothing already on the card is erased '
              'or changed, and the card\'s own master key is left alone — but '
              'only do this to a card you own.',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: KioskColors.onSurfaceMuted,
                height: 1.45,
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Yes, set it up'),
          ),
        ],
      ),
    );
  }

  Future<void> _run() async {
    setState(() {
      _phase = SetupPhase.running;
      _currentStep = null;
      _completed.clear();
      _errorTitle = null;
      _errorDetail = null;
    });

    try {
      final snapshot = await widget.gateway.provisionCard(
        onStep: (step) {
          if (!mounted) return;
          setState(() {
            final previous = _currentStep;
            if (previous != null) _completed.add(previous);
            _currentStep = step;
          });
        },
      );
      if (!mounted) return;
      setState(() {
        _completed.addAll(_visibleSteps);
        _currentStep = null;
        _result = snapshot;
        _phase = SetupPhase.done;
      });
    } catch (error) {
      if (!mounted) return;
      final described = _describe(error);
      setState(() {
        _phase = SetupPhase.failed;
        _errorTitle = described.$1;
        _errorDetail = described.$2;
      });
    }
  }

  (String, String) _describe(Object error) {
    switch (error) {
      case ProvisioningFailedException e:
        return (
          '${e.step.label} failed',
          '${_causeMessage(e.cause)}\n\nNothing after this step was written. '
              'It is safe to try again.',
        );
      case NoCardException e:
        return ('No card detected', e.message);
      case DesfireException e:
        return ('Setup failed', e.message);
      default:
        return ('Setup failed', error.toString());
    }
  }

  String _causeMessage(Object cause) =>
      cause is DesfireException ? cause.message : cause.toString();

  Future<void> _cancel() async {
    await widget.gateway.cancel();
    if (!mounted) return;
    Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Set up card'),
        automaticallyImplyLeading: _phase != SetupPhase.running,
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
          children: switch (_phase) {
            SetupPhase.explaining => _explaining(context),
            SetupPhase.running => _running(context),
            SetupPhase.done => _done(context),
            SetupPhase.failed => _failed(context),
          },
        ),
      ),
    );
  }

  List<Widget> _explaining(BuildContext context) {
    final theme = Theme.of(context);
    return [
      const _SetupHeader(
        icon: Icons.credit_card_off_rounded,
        color: KioskColors.primary,
        title: 'This card is not set up',
        detail:
            'It is a DESFire card, but it does not carry the top-up '
            'application yet.',
      ),
      const SizedBox(height: 24),
      _InfoPanel(
        title: 'What setting it up does',
        lines: const [
          'Creates one new application on the card and gives it its own keys.',
          'Creates a balance file inside that application, starting at €0.00.',
          'Leaves everything else on the card untouched, including the card\'s '
              'own master key.',
        ],
      ),
      const SizedBox(height: 16),
      _InfoPanel(
        title: 'Before you tap',
        accent: KioskColors.error,
        lines: const [
          'Only do this to a card you own. Office badges, hotel keys and '
              'transit cards are DESFire too and look the same from here.',
          'This app cannot undo it.',
          'It takes a few seconds and about twenty exchanges with the card, so '
              'the card has to stay still the whole time.',
        ],
      ),
      const SizedBox(height: 28),
      FilledButton.icon(
        onPressed: _confirmAndRun,
        icon: const Icon(Icons.auto_fix_high_rounded),
        label: const Text('Set up this card'),
      ),
      const SizedBox(height: 12),
      TextButton(
        onPressed: () => Navigator.of(context).pop(),
        child: const Text('Not now'),
      ),
      const SizedBox(height: 20),
      Text(
        'AID 0x${AppConfig.applicationId.toRadixString(16).padLeft(6, '0').toUpperCase()} '
        '· ${AppConfig.numApplicationKeys} AES-128 keys · value file '
        '0x${AppConfig.valueFileNo.toRadixString(16).padLeft(2, '0').toUpperCase()}',
        textAlign: TextAlign.center,
        style: theme.textTheme.bodySmall?.copyWith(
          color: KioskColors.onSurfaceMuted,
        ),
      ),
    ];
  }

  List<Widget> _running(BuildContext context) {
    final theme = Theme.of(context);
    final started = _currentStep != null;
    return [
      SizedBox(
        height: 170,
        child: Center(
          child: NfcPulse(
            color: started ? KioskColors.secondary : KioskColors.primary,
          ),
        ),
      ),
      const SizedBox(height: 16),
      Text(
        started ? 'Keep the card still' : 'Hold your card to the phone',
        textAlign: TextAlign.center,
        style: theme.textTheme.titleMedium?.copyWith(
          fontWeight: FontWeight.w700,
        ),
      ),
      const SizedBox(height: 8),
      Text(
        started
            ? 'Writing to the card. If it moves now, setup stops part-way — '
                  'tap again and it picks up where it left off.'
            : 'Hold the card flat against the back of the phone and leave it '
                  'there.',
        textAlign: TextAlign.center,
        style: theme.textTheme.bodyMedium?.copyWith(
          color: KioskColors.onSurfaceMuted,
          height: 1.4,
        ),
      ),
      const SizedBox(height: 26),
      _StepChecklist(
        steps: _visibleSteps,
        current: _currentStep,
        completed: _completed,
      ),
      const SizedBox(height: 24),
      OutlinedButton(onPressed: _cancel, child: const Text('Cancel')),
    ];
  }

  List<Widget> _done(BuildContext context) {
    final theme = Theme.of(context);
    return [
      const _SetupHeader(
        icon: Icons.check_circle_rounded,
        color: KioskColors.secondary,
        title: 'Card is ready',
        detail: 'The application and balance file are on the card.',
      ),
      const SizedBox(height: 20),
      Text(
        '€${formatCents(_result?.balanceCents ?? 0)}',
        textAlign: TextAlign.center,
        style: theme.textTheme.displaySmall?.copyWith(
          fontWeight: FontWeight.w700,
          color: KioskColors.secondary,
        ),
      ),
      const SizedBox(height: 8),
      Text(
        'Balance read back from the card.',
        textAlign: TextAlign.center,
        style: theme.textTheme.bodyMedium?.copyWith(
          color: KioskColors.onSurfaceMuted,
        ),
      ),
      const SizedBox(height: 30),
      FilledButton(
        onPressed: () => Navigator.of(context).pop(_result),
        child: const Text('Done'),
      ),
    ];
  }

  List<Widget> _failed(BuildContext context) {
    return [
      _SetupHeader(
        icon: Icons.error_rounded,
        color: KioskColors.error,
        title: _errorTitle ?? 'Setup failed',
        detail: _errorDetail ?? '',
      ),
      const SizedBox(height: 24),
      _StepChecklist(
        steps: _visibleSteps,
        current: null,
        completed: _completed,
        failed: _currentStep,
      ),
      const SizedBox(height: 28),
      FilledButton(onPressed: _run, child: const Text('Try again')),
      const SizedBox(height: 12),
      TextButton(
        onPressed: () => Navigator.of(context).pop(),
        child: const Text('Cancel'),
      ),
    ];
  }
}

class _SetupHeader extends StatelessWidget {
  const _SetupHeader({
    required this.icon,
    required this.color,
    required this.title,
    required this.detail,
  });

  final IconData icon;
  final Color color;
  final String title;
  final String detail;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      children: [
        const SizedBox(height: 12),
        Container(
          width: 96,
          height: 96,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: color.withValues(alpha: 0.14),
            border: Border.all(color: color.withValues(alpha: 0.5), width: 2),
          ),
          child: Icon(icon, size: 46, color: color),
        ),
        const SizedBox(height: 20),
        Text(
          title,
          textAlign: TextAlign.center,
          style: theme.textTheme.titleLarge?.copyWith(
            fontWeight: FontWeight.w700,
          ),
        ),
        if (detail.isNotEmpty) ...[
          const SizedBox(height: 10),
          Text(
            detail,
            textAlign: TextAlign.center,
            style: theme.textTheme.bodyMedium?.copyWith(
              color: KioskColors.onSurfaceMuted,
              height: 1.45,
            ),
          ),
        ],
      ],
    );
  }
}

class _InfoPanel extends StatelessWidget {
  const _InfoPanel({
    required this.title,
    required this.lines,
    this.accent = KioskColors.primary,
  });

  final String title;
  final List<String> lines;
  final Color accent;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: KioskColors.surfaceHigh,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: accent.withValues(alpha: 0.35)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: theme.textTheme.titleSmall?.copyWith(
              fontWeight: FontWeight.w700,
              color: accent,
            ),
          ),
          const SizedBox(height: 10),
          for (final line in lines)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Padding(
                    padding: const EdgeInsets.only(top: 7, right: 10),
                    child: Container(
                      width: 5,
                      height: 5,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: accent.withValues(alpha: 0.8),
                      ),
                    ),
                  ),
                  Expanded(
                    child: Text(
                      line,
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: KioskColors.onSurface,
                        height: 1.45,
                      ),
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

class _StepChecklist extends StatelessWidget {
  const _StepChecklist({
    required this.steps,
    required this.current,
    required this.completed,
    this.failed,
  });

  final List<ProvisioningStep> steps;
  final ProvisioningStep? current;
  final Set<ProvisioningStep> completed;
  final ProvisioningStep? failed;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
      decoration: BoxDecoration(
        color: KioskColors.surfaceHigh,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: KioskColors.outline),
      ),
      child: Column(
        children: [
          for (final step in steps)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Row(
                children: [
                  SizedBox(width: 26, height: 26, child: _marker(step)),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Text(
                      step.label,
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: _isPending(step)
                            ? KioskColors.onSurfaceMuted
                            : KioskColors.onSurface,
                        fontWeight: step == current
                            ? FontWeight.w700
                            : FontWeight.w400,
                      ),
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  bool _isPending(ProvisioningStep step) =>
      step != current && step != failed && !completed.contains(step);

  Widget _marker(ProvisioningStep step) {
    if (step == failed) {
      return const Icon(
        Icons.close_rounded,
        size: 22,
        color: KioskColors.error,
      );
    }
    if (completed.contains(step)) {
      return const Icon(
        Icons.check_circle_rounded,
        size: 22,
        color: KioskColors.secondary,
      );
    }
    if (step == current) {
      return const Padding(
        padding: EdgeInsets.all(3),
        child: CircularProgressIndicator(
          strokeWidth: 2.4,
          color: KioskColors.primary,
        ),
      );
    }
    return Center(
      child: Container(
        width: 9,
        height: 9,
        decoration: const BoxDecoration(
          shape: BoxShape.circle,
          color: KioskColors.outline,
        ),
      ),
    );
  }
}
