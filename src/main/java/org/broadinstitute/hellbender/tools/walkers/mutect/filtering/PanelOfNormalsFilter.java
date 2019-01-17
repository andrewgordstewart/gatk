package org.broadinstitute.hellbender.tools.walkers.mutect.filtering;

import htsjdk.variant.variantcontext.VariantContext;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;

import java.util.Collections;
import java.util.List;

public class PanelOfNormalsFilter extends HardFilter {
    @Override
    public boolean isArtifact(final VariantContext vc, final Mutect2FilteringInfo filteringInfo) {
        return vc.hasAttribute(GATKVCFConstants.IN_PON_VCF_ATTRIBUTE);
    }

    @Override
    public String filterName() {
        return GATKVCFConstants.PON_FILTER_NAME;
    }

    @Override
    protected List<String> requiredAnnotations() { return Collections.emptyList(); }
}
