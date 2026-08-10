package com.example.docushift_mobile

import android.os.Bundle
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterFragmentActivity() {

    lateinit var coordinator: ImageToPdfCoordinator
        private set

    lateinit var pdfToPngCoordinator: PdfToPngCoordinator
        private set

    lateinit var pdfToJpgCoordinator: PdfToJpgCoordinator
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        coordinator = ImageToPdfCoordinator(this)
        coordinator.registerLaunchers()

        pdfToPngCoordinator = PdfToPngCoordinator(this)
        pdfToPngCoordinator.registerLaunchers()

        pdfToJpgCoordinator = PdfToJpgCoordinator(this)
        pdfToJpgCoordinator.registerLaunchers()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        ImageToPdfPlugin.registerWith(flutterEngine, this)
        PdfToPngPlugin.registerWith(flutterEngine, this)
        PdfToJpgPlugin.registerWith(flutterEngine, this)
    }

    override fun onDestroy() {
        coordinator.onDestroy()
        pdfToPngCoordinator.onDestroy()
        pdfToJpgCoordinator.onDestroy()
        super.onDestroy()
    }
}
