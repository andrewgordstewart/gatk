package org.broadinstitute.hellbender.tools.walkers.mutect.filtering;

import htsjdk.variant.variantcontext.VariantContext;
import org.broadinstitute.hellbender.tools.walkers.annotator.FragmentLength;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;

import java.util.Collections;
import java.util.List;

public class FragmentLengthFilter extends HardFilter {
    @Override
    public boolean isArtifact(final VariantContext vc, final Mutect2FilteringInfo filteringInfo) {
        final List<Integer> fragmentLengthByAllele = vc.getAttributeAsIntList(FragmentLength.KEY, 0);

        return Math.abs(fragmentLengthByAllele.get(1) - fragmentLengthByAllele.get(0)) > filteringInfo.getMTFAC().maxMedianFragmentLengthDifference;
    }

    @Override
    public String filterName() {
        return GATKVCFConstants.MEDIAN_FRAGMENT_LENGTH_DIFFERENCE_FILTER_NAME;
    }

    @Override
    protected List<String> requiredAnnotations() { return Collections.singletonList(FragmentLength.KEY); }
}
