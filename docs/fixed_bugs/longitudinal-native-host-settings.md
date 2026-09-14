# Bundled native calculation must restore host settings

The real-host native coexistence test loads OpenCV through the host and through
the private registration loader in both possible orders. The original packaged
worker left the host's OpenCV thread count at 1 after the host had selected 2.
Separate Java classloaders did not isolate native process state on this machine.
Actual Gaussian image values were unchanged, but the host setting was not.

The experimental worker now saves the current thread/OpenCL settings, applies
the already-checked one-thread/non-OpenCL policy within each calculation, and
restores the caller's settings in a finally block. The calculation itself is
unchanged. Its estimate and diagnostic entry points share one synchronized scope.

Regression guard: tuning-folder code/HostNativeCoexistenceProbe.java and
code/test_r20_native_coexistence.py. Both real loading orders check native image
values and settings after runtime inspection, successful estimation, and an
exception inside the settings scope. Retain the failing old-package result.
The production plugin is unchanged.

This proves restoration after calls, not isolation from another plugin changing
native global settings concurrently. Concurrent unrelated native operations are
not a supported guarantee of the experimental adapter.
