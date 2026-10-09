import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'features/home/home_screen.dart';

void main() {
  runApp(const ProviderScope(child: PionirApp()));
}

class PionirApp extends StatelessWidget {
  const PionirApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'PIONIR',
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.teal)),
      home: const HomeScreen(),
    );
  }
}
