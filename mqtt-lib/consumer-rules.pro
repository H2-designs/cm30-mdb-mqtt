# Shipped INSIDE the AAR (consumerProguardFiles): R8 merges these into any app that bundles
# mqtt-lib, so the app developer never edits their proguard config.
#
# hardware-lib finds MqttLib/RabbahLog via reflection (attachRabbahMqtt), and command dispatch
# uses Function1 listeners across the reflective boundary - keep the whole surface intact.
-keep class com.rabbah.mqtt.** { *; }

# CROSS-KEEP (2.5.3): mqtt-lib reaches hardware-lib by reflection
# (Class.forName("com.rabbah.mdb.HardwareLib") - auto-attach / attachHardware / version).
# Protect the TARGET from here too, so a fleet app that updates only mqtt-lib still gets a
# working bridge with whatever hardware-lib it already bundles - seen in the field: build
# 1.0.235 shipped mqtt-lib 2.5.2 next to an old unprotected hardware-lib, R8 renamed it,
# and the device reported "hardware-lib absent" although the engine was running fine.
-keep class com.rabbah.mdb.** { *; }
