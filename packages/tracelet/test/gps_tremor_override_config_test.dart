import 'package:flutter_test/flutter_test.dart';
import 'package:tracelet/tracelet.dart';
import 'package:tracelet_platform_interface/tracelet_platform_interface.dart';

// An omitted setConfig field must not restore an override the app disabled.
void main() {
  test(
    'default preserves the override without leaking into partial updates',
    () {
      const motion = MotionConfig();
      expect(motion.enableGpsTremorOverride, isTrue);
      expect(motion.toMap(), isEmpty);
      expect(motion.toTlConfig().enableGpsTremorOverride, isNull);
      expect(motion.resolved().toTlConfig().enableGpsTremorOverride, isTrue);
    },
  );

  for (final enabled in [false, true]) {
    test('explicit $enabled survives ready, map and Pigeon round trips', () {
      final config = Config(
        motion: MotionConfig(enableGpsTremorOverride: enabled),
      );
      final restored = Config.fromMap(config.toMap());
      expect(restored.motion.enableGpsTremorOverride, enabled);
      expect(restored.toMap(), config.toMap());
      final wire = restored.resolved().toTlConfig().motion;
      final decoded = TlMotionConfig.decode(wire.encode());
      expect(decoded.enableGpsTremorOverride, enabled);
    });
  }

  test('partial updates preserve false and can restore true explicitly', () {
    const disabled = MotionConfig(enableGpsTremorOverride: false);
    expect(
      disabled.mergedWith(const MotionConfig()).enableGpsTremorOverride,
      isFalse,
    );
    expect(
      disabled
          .mergedWith(const MotionConfig(enableGpsTremorOverride: true))
          .enableGpsTremorOverride,
      isTrue,
    );
    expect(disabled, isNot(const MotionConfig()));
    expect(disabled, MotionConfig.fromMap(disabled.toMap()));
  });
}
