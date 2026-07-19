/// MIFARE DESFire EV2/EV3 NFC top-up app.
///
/// See README.md for the security model. In short: this build has AES keys
/// compiled into it and is for bench testing only.
library;

import 'package:flutter/material.dart';

import 'nfc/card_gateway.dart';
import 'ui/home_screen.dart';
import 'ui/theme.dart';

void main() {
  runApp(const TopUpApp());
}

/// Root widget.
class TopUpApp extends StatelessWidget {
  const TopUpApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Card top-up',
      debugShowCheckedModeBanner: false,
      theme: buildKioskTheme(),
      home: HomeScreen(gateway: CardGateway()),
    );
  }
}
