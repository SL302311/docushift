import 'package:flutter/material.dart';
import 'home_page.dart';

void main() {
  runApp(const DocuShiftApp());
}

class DocuShiftApp extends StatelessWidget {
  const DocuShiftApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'DocuShift',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blueGrey),
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}
