package com.macro.recognition;

/** 单次匹配使用的缩放上下限；原有调用继续使用默认范围，不改变 MR 配置。 */
public record TemplateScaleRange(double minimum, double maximum) {
    public static final TemplateScaleRange DEFAULT = new TemplateScaleRange(2.0 / 3.0, 1.5);

    public TemplateScaleRange {
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum)
                || minimum < .01 || minimum > 1 || maximum < 1 || maximum > 4) {
            throw new IllegalArgumentException("缩放下限需为 1%～100%，上限需为 100%～400%");
        }
    }

    /** 保留常用比例并包含两个边界，不超过十个粗查比例。 */
    double[] coarseScales() {
        if (equals(DEFAULT)) return TemplateScaleVariants.COARSE_SCALES;
        java.util.LinkedHashSet<Double> scales = new java.util.LinkedHashSet<>();
        for (double scale : TemplateScaleVariants.COARSE_SCALES) {
            if (scale != TemplateScaleVariants.MIN_SCALE && scale != TemplateScaleVariants.MAX_SCALE
                    && scale >= minimum && scale <= maximum) scales.add(scale);
        }
        if (minimum < 1) scales.add(minimum);
        if (maximum > 1) scales.add(maximum);
        // 窄范围过滤掉常用比例后，在上下限之间补充有限候选。
        for (int step = 1; scales.size() < 10 && step <= 5; step++) {
            if (minimum < 1) scales.add(1 - (1 - minimum) * step / 5);
            if (scales.size() < 10 && maximum > 1) scales.add(1 + (maximum - 1) * step / 5);
        }
        return scales.stream().mapToDouble(Double::doubleValue).toArray();
    }
}
