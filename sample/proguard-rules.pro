# Sample app: relies on the :core / :ui consumer rules for the SDK surface.
# Keep the sample's own entrypoints (referenced from the manifest by name).
-keep class com.zerosettle.sample.SampleApplication { *; }
-keep class com.zerosettle.sample.SampleActivity { *; }
