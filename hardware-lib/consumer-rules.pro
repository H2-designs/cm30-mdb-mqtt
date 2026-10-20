# Shipped INSIDE the AAR (consumerProguardFiles): R8 merges these into any app that bundles
# hardware-lib, so the app developer never edits their proguard config.
#
# Why they exist: mqtt-lib reaches this library ONLY via reflection
# (Class.forName("com.rabbah.mdb.HardwareLib") for auto-attach / attachHardware / version).
# R8 cannot see reflection - without these keeps, a minified app that has no direct code
# reference to hardware-lib gets it STRIPPED from the APK entirely, and the dashboard reports
# "attachHardware FAILED: hardware-lib absent ... (bundled: absent)" (seen live on D-0416).
-keep class com.rabbah.mdb.** { *; }
