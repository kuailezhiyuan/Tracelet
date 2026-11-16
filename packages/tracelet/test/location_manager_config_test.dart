import 'package:flutter_test/flutter_test.dart';
import 'package:tracelet/tracelet.dart';
import 'package:tracelet_platform_interface/tracelet_platform_interface.dart';

void main() {
  test('defaults keep automatic selection and omit partial update fields', () {
    const android = AndroidConfig();
    expect(android.useLocationManager, isFalse);
    expect(android.stationaryLocationUpdateInterval, 0);
    expect(android.toMap(), isEmpty);
    expect(android.toTlConfig().useLocationManager, isNull);
    expect(android.toTlConfig().stationaryLocationUpdateInterval, isNull);
    expect(android.resolved().toTlConfig().useLocationManager, isFalse);
    expect(android.resolved().toTlConfig().stationaryLocationUpdateInterval, 0);
  });

  test('explicit options survive map and Pigeon round trips', () {
    const android = AndroidConfig(
      useLocationManager: true,
      stationaryLocationUpdateInterval: 10000,
    );
    final restored = AndroidConfig.fromMap(android.toMap());
    expect(restored, android);
    final wire = restored.toTlConfig();
    final decoded = TlAndroidConfig.decode(wire.encode());
    expect(decoded.useLocationManager, isTrue);
    expect(decoded.stationaryLocationUpdateInterval, 10000);
    expect(android.copyWith(), android);
    expect(android.resolved().useLocationManager, isTrue);
  });

  test('partial updates preserve options and explicit defaults reset them', () {
    const android = AndroidConfig(
      useLocationManager: true,
      stationaryLocationUpdateInterval: 10000,
    );
    expect(android.mergedWith(const AndroidConfig()), android);
    final reset = android.mergedWith(
      const AndroidConfig(
        useLocationManager: false,
        stationaryLocationUpdateInterval: 0,
      ),
    );
    expect(reset.useLocationManager, isFalse);
    expect(reset.stationaryLocationUpdateInterval, 0);
    expect(reset.toMap(), {
      'useLocationManager': false,
      'stationaryLocationUpdateInterval': 0,
    });
    expect(reset, isNot(const AndroidConfig()));
    expect(
      android.copyWith(useLocationManager: false).useLocationManager,
      isFalse,
    );
  });

  test('negative stationary intervals are rejected', () {
    expect(
      () => AndroidConfig(stationaryLocationUpdateInterval: -1),
      throwsAssertionError,
    );
  });
}
