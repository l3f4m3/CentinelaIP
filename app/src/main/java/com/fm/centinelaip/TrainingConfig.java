package com.fm.centinelaip;

final class TrainingConfig {
    final String endpoint;
    final String token;
    final int epochs;
    final boolean autoCuration;
    final boolean purgeRejected;
    final float acceptAt;
    final float rejectBelow;
    final String jobId;

    TrainingConfig(String endpoint, String token, int epochs, boolean autoCuration,
                   boolean purgeRejected, float acceptAt, float rejectBelow, String jobId) {
        this.endpoint = endpoint == null ? "" : endpoint.trim().replaceAll("/+$", "");
        this.token = token == null ? "" : token;
        this.epochs = Math.max(1, Math.min(300, epochs));
        this.autoCuration = autoCuration;
        this.purgeRejected = purgeRejected;
        this.acceptAt = Math.max(.05f, Math.min(.99f, acceptAt));
        this.rejectBelow = Math.max(.01f, Math.min(this.acceptAt - .01f, rejectBelow));
        this.jobId = jobId == null ? "" : jobId;
    }
}
