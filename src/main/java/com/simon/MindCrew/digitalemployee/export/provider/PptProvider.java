package com.simon.MindCrew.digitalemployee.export.provider;

public interface PptProvider {

    String type();

    byte[] generate(PptProviderRequest request, PptProviderConfig config);

    default byte[] generate(PptProviderRequest request, PptProviderConfig config,
                            ProgressListener progressListener) {
        return generate(request, config);
    }

    @FunctionalInterface
    interface ProgressListener {
        void onProgress(int percentage, String stage);

        static ProgressListener noop() {
            return (percentage, stage) -> { };
        }
    }
}
