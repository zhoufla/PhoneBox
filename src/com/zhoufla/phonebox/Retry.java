package com.zhoufla.phonebox;

/**
 * 投递失败退避：1/2/4/8/16/30 分钟，之后固定 30 分钟。
 * 次数上限由通道配置决定，夹在 1~50。
 */
public final class Retry {

    private Retry() {
    }

    public static long backoffMs(int tries) {
        int t = tries < 1 ? 1 : tries;
        return 60L * 1000L * Math.min(30, 1 << Math.min(t - 1, 5));
    }

    public static int clampTries(int maxTries) {
        if (maxTries < 1) {
            return 1;
        }
        return Math.min(maxTries, 50);
    }

    /** tries 是本次失败之前的次数；返回 true 表示这次失败后放弃。 */
    public static boolean giveUp(int tries, int maxTries) {
        return tries + 1 >= clampTries(maxTries);
    }
}
