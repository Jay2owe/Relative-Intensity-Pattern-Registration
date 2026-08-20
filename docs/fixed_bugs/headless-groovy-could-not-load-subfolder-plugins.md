# Headless Groovy could not load plugins from subfolders
**Date**: 2026-08-17
**Files changed**: `library/benchmark/run_external_full_no_dialog.groovy`
**Guard**: guard comments in `library/benchmark/run_external_full_no_dialog.groovy`

## What went wrong
The no-dialog Fiji benchmark found plugins in Fiji's root `plugins` and `jars` folders but failed at Image Stabilizer with `ClassNotFoundException`. Image Stabilizer is installed as loose class files under `plugins/Image Stabilizer`, and Fast4DReg is installed as a jar under `plugins/Fast4DReg`; neither subfolder was visible to the Groovy class loader used for the benchmark class.

## The broken pattern
```groovy
this.class.classLoader.addURL(new File(root, 'target/test-classes').toURI().toURL())
Class.forName('logratio.ExternalPluginComparisonStacks', true, this.class.classLoader)
// The loader cannot see plugin classes below Fiji's plugin-root classpath.
```

## The fix
The script adds the installed Image Stabilizer class directory and every installed Fast4DReg jar in its plugin subfolder to the same loader before loading the benchmark. It also exits Java with code 1 when the invoked benchmark throws, because SciJava otherwise logs the script error while the process exits successfully.

## Why it matters
Without these loader paths and the non-zero exit, the external-method stage can omit installed methods while the pipeline records a successful completion.
