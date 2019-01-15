package org.broadinstitute.hellbender.tools.walkers.mutect.filtering;

import htsjdk.variant.variantcontext.VariantContext;
import org.broadinstitute.hellbender.utils.MathUtils;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;

import java.util.Collections;
import java.util.List;

public class NRatioFilter extends HardFilter {
    @Override
    public boolean isArtifact(final VariantContext vc, final Mutect2FilteringInfo filteringInfo) {
        final int[] ADs = sumADsOverSamples(vc, filteringInfo.getNormalSamples(), true, true);
        final int altCount = (int) MathUtils.sum(ADs) - ADs[0];

        // if there is no NCount annotation or the altCount is 0, don't apply the filter
        if (altCount == 0 ) {
            return false;
        }

        final int NCount = vc.getAttributeAsInt(GATKVCFConstants.N_COUNT_KEY,0);

        return (double) NCount / altCount >= filteringInfo.getMTFAC().nRatio;
    }

    public String filterName() {
        return GATKVCFConstants.N_RATIO_FILTER_NAME;
    }

    protected List<String> requiredAnnotations() { return Collections.singletonList(GATKVCFConstants.N_COUNT_KEY); }
}
