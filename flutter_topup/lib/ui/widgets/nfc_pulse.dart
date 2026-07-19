/// Pulsing NFC indicator used on the tap-to-scan sheet.
library;

import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../theme.dart';

/// Three concentric rings that expand and fade outwards behind an NFC glyph.
class NfcPulse extends StatefulWidget {
  const NfcPulse({
    super.key,
    this.size = 168,
    this.color = KioskColors.primary,
    this.active = true,
  });

  /// Diameter of the outermost ring at full expansion.
  final double size;

  /// Ring and glyph colour.
  final Color color;

  /// When false the animation freezes and the rings are hidden.
  final bool active;

  @override
  State<NfcPulse> createState() => _NfcPulseState();
}

class _NfcPulseState extends State<NfcPulse>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 2200),
  );

  @override
  void initState() {
    super.initState();
    if (widget.active) _controller.repeat();
  }

  @override
  void didUpdateWidget(NfcPulse oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.active && !_controller.isAnimating) {
      _controller.repeat();
    } else if (!widget.active && _controller.isAnimating) {
      _controller.stop();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: widget.size,
      height: widget.size,
      child: AnimatedBuilder(
        animation: _controller,
        builder: (context, child) {
          return CustomPaint(
            painter: _PulsePainter(
              progress: _controller.value,
              color: widget.color,
              visible: widget.active,
            ),
            child: child,
          );
        },
        child: Center(
          child: Container(
            width: widget.size * 0.44,
            height: widget.size * 0.44,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: widget.color.withValues(alpha: 0.16),
              border: Border.all(color: widget.color.withValues(alpha: 0.5)),
            ),
            child: Icon(
              Icons.contactless_rounded,
              size: widget.size * 0.24,
              color: widget.color,
            ),
          ),
        ),
      ),
    );
  }
}

class _PulsePainter extends CustomPainter {
  _PulsePainter({
    required this.progress,
    required this.color,
    required this.visible,
  });

  final double progress;
  final Color color;
  final bool visible;

  static const int _ringCount = 3;

  @override
  void paint(Canvas canvas, Size size) {
    if (!visible) return;
    final center = Offset(size.width / 2, size.height / 2);
    final maxRadius = math.min(size.width, size.height) / 2;
    final minRadius = maxRadius * 0.24;

    for (var i = 0; i < _ringCount; i++) {
      final t = (progress + i / _ringCount) % 1.0;
      // Ease-out so rings move fast at first, then linger at the edge.
      final eased = 1 - math.pow(1 - t, 2.2).toDouble();
      final radius = minRadius + (maxRadius - minRadius) * eased;
      final opacity = (1 - t) * 0.55;
      if (opacity <= 0.01) continue;
      canvas.drawCircle(
        center,
        radius,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2.0
          ..color = color.withValues(alpha: opacity),
      );
    }
  }

  @override
  bool shouldRepaint(_PulsePainter oldDelegate) =>
      oldDelegate.progress != progress ||
      oldDelegate.color != color ||
      oldDelegate.visible != visible;
}
