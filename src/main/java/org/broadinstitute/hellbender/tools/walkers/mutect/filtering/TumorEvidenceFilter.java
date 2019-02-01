package org.broadinstitute.hellbender.tools.walkers.mutect.filtering;

import htsjdk.variant.variantcontext.VariantContext;
import org.broadinstitute.hellbender.utils.GATKProtectedVariantContextUtils;
import org.broadinstitute.hellbender.utils.MathUtils;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class TumorEvidenceFilter extends Mutect2VariantFilter {
    @Override
    public ErrorType errorType() { return ErrorType.SEQUENCING; }

    @Override
    public double calculateErrorProbability(final VariantContext vc, final Mutect2FilteringEngine filteringInfo) {
        final double[] tumorLods = GATKProtectedVariantContextUtils.getAttributeAsDoubleArray(vc, GATKVCFConstants.TUMOR_LOD_KEY);
        final int[] ADs = filteringInfo.sumADsOverSamples(vc, true, false);
        final int maxIndex = MathUtils.maxElementIndex(tumorLods);
        final double tumorLog10Odds = tumorLods[maxIndex];
        final int refCount = ADs[0];
        final int altCount = ADs[maxIndex + 1];
        final double tumorLog10OddsCorrectedForClustering = filteringInfo.clusteringCorrectedLog10Odds(tumorLog10Odds, altCount, refCount);
        return posteriorProbabilityOfError(tumorLog10OddsCorrectedForClustering, filteringInfo.getLog10PriorOfSomaticVariant(vc));
    }

    @Override
    public Optional<String> phredScaledPosteriorAnnotationName() {
        return Optional.of(GATKVCFConstants.SEQUENCING_QUAL_VCF_ATTRIBUTE);
    }

    @Override
    public String filterName() {
        return GATKVCFConstants.TUMOR_EVIDENCE_FILTER_NAME;
    }

    @Override
    protected List<String> requiredAnnotations() { return Collections.singletonList(GATKVCFConstants.TUMOR_LOD_KEY); }

}
