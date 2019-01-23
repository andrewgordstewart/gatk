package org.broadinstitute.hellbender.tools.walkers.mutect.filtering;

import com.google.common.annotations.VisibleForTesting;
import htsjdk.samtools.util.OverlapDetector;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLine;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.tools.walkers.contamination.ContaminationRecord;
import org.broadinstitute.hellbender.tools.walkers.contamination.MinorAlleleFractionRecord;
import org.broadinstitute.hellbender.tools.walkers.mutect.Mutect2Engine;
import org.broadinstitute.hellbender.tools.walkers.mutect.MutectStats;
import org.broadinstitute.hellbender.utils.MathUtils;
import org.broadinstitute.hellbender.utils.QualityUtils;
import org.broadinstitute.hellbender.utils.param.ParamUtils;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Simple class to hold all information needed by Mutect2 filters, including: the M2FilteringArgumentCollection,
 * the set of normal samples, the samples' contamination, and the tumor sample CNV segments
 */
public class Mutect2FilteringInfo {
    private static final double FIRST_PASS_THRESHOLD = 0.5;
    private static final double EPSILON = 1.0e-10;

    private final List<Mutect2VariantFilter> filters = new ArrayList<>();

    /**
     * CONSTANT PARAMETERS
     */
    private final M2FiltersArgumentCollection MTFAC;
    private final Set<String> normalSamples;
    private OptionalLong totalCallableSites = OptionalLong.empty();

    /**
     * LEARNED PARAMETERS
     */
    private double log10PriorOfSomaticSNV;
    private double log10PriorOfSomaticIndel;
    private double priorProbOfArtifactVersusVariant;
    private double artifactProbabilityThreshold = FIRST_PASS_THRESHOLD;

    // TODO: make this private to BadHaplotypeFilter
    // TODO: make it probabilistic and eliminate the FIRST_PASS_THRESHOLD
    // for each PID, the positions with PGTs of filtered genotypes
    private final Map<String, ImmutablePair<Integer, Set<String>>> filteredPhasedCalls;

    /**
     * DATA ACCUMULATED ON EACH PASS OF {@link FilterMutectCalls}
     */
    final List<Double> firstPassArtifactProbabilities = new ArrayList<>();

    // TODO: absorb into outputStats
    private final MutableDouble realVariantCount = new MutableDouble(0);
    private final MutableDouble realSNVCount = new MutableDouble(0);
    private final MutableDouble realIndelCount = new MutableDouble(0);
    private final MutableDouble technicalArtifactCount = new MutableDouble(0);

    private final OutputStats outputStats = new OutputStats();

    public Mutect2FilteringInfo(M2FiltersArgumentCollection MTFAC, final VCFHeader vcfHeader) {
        this.MTFAC = MTFAC;
        normalSamples = vcfHeader.getMetaDataInInputOrder().stream()
                .filter(line -> line.getKey().equals(Mutect2Engine.NORMAL_SAMPLE_KEY_IN_VCF_HEADER))
                .map(VCFHeaderLine::getValue)
                .collect(Collectors.toSet());

        filteredPhasedCalls = new HashMap<>();

        log10PriorOfSomaticSNV = MTFAC.log10PriorProbOfSomaticSNV;
        log10PriorOfSomaticIndel = MTFAC.log10PriorProbOfSomaticIndel;

        priorProbOfArtifactVersusVariant = MTFAC.initialPriorOfArtifactVersusVariant;

        buildFiltersList(MTFAC);
    }

    private void buildFiltersList(final M2FiltersArgumentCollection MTFAC) {
        filters.add(new TumorEvidenceFilter());
        filters.add(new BaseQualityFilter(MTFAC.minMedianBaseQuality));
        filters.add(new MappingQualityFilter(MTFAC.minMedianMappingQuality, MTFAC.longIndelLength));
        filters.add(new DuplicatedAltReadFilter(MTFAC.uniqueAltReadCount));
        filters.add(new StrandArtifactFilter());
        filters.add(new ContaminationFilter(MTFAC.contaminationTables, MTFAC.contaminationEstimate));
        filters.add(new PanelOfNormalsFilter());
        filters.add(new NormalArtifactFilter());
        filters.add(new ReadOrientationFilter());
        filters.add(new NRatioFilter(MTFAC.nRatio));
        filters.add(new StrictStrandBiasFilter(MTFAC.minReadsOnEachStrand));
        filters.add(new ReadPositionFilter(MTFAC.minMedianReadPosition));

        if (MTFAC.mitochondria) {
            filters.add(new LogOddsOverDepthFilter(MTFAC.minLog10OddsDividedByDepth));
            filters.add(new ChimericOriginalAlignmentFilter(MTFAC.maxNuMTFraction));
        } else {
            filters.add(new ClusteredEventsFilter(MTFAC.maxEventsInRegion));
            filters.add(new MultiallelicFilter(MTFAC.numAltAllelesThreshold));
            filters.add(new FragmentLengthFilter(MTFAC.maxMedianFragmentLengthDifference));
            filters.add(new PolymeraseSlippageFilter(MTFAC.minSlippageLength, MTFAC.slippageRate));
            filters.add(new FilteredHaplotypeFilter(MTFAC.maxDistanceToFilteredCallOnSameHaplotype));
            filters.add(new GermlineFilter(MTFAC.tumorSegmentationTables));
        }
    }

    public void inputMutectStats(final File mutectStatsTable) {
        final Map<String, Double> stats = MutectStats.readFromFile(mutectStatsTable).stream()
                .collect(Collectors.toMap(MutectStats::getStatistic, MutectStats::getValue));

        totalCallableSites = OptionalLong.of(Math.round(stats.get(Mutect2Engine.CALLABLE_SITES_NAME)));
    }

    public Set<String> getNormalSamples() {
        return normalSamples;
    }

    public Map<String, ImmutablePair<Integer, Set<String>>> getFilteredPhasedCalls() {
        return filteredPhasedCalls;
    }

    public double getArtifactProbabilityThreshold() {
        return artifactProbabilityThreshold;
    }

    public double getLog10PriorOfSomaticVariant(final VariantContext vc) {
        return vc.isSNP() ? (MathUtils.LOG10_ONE_THIRD + log10PriorOfSomaticSNV) : log10PriorOfSomaticIndel;
    }

    public double getPriorProbOfArtifactVersusVariant() { return priorProbOfArtifactVersusVariant; }

    public void recordFilteredHaplotypes(final VariantContext vc) {
        final Map<String, Set<String>> phasedGTsForEachPhaseID = vc.getGenotypes().stream()
                .filter(gt -> !normalSamples.contains(gt.getSampleName()))
                .filter(Mutect2FilteringInfo::hasPhaseInfo)
                .collect(Collectors.groupingBy(g -> (String) g.getExtendedAttribute(GATKVCFConstants.HAPLOTYPE_CALLER_PHASING_ID_KEY, ""),
                        Collectors.mapping(g -> (String) g.getExtendedAttribute(GATKVCFConstants.HAPLOTYPE_CALLER_PHASING_GT_KEY, ""), Collectors.toSet())));

        for (final Map.Entry<String, Set<String>> pidAndPgts : phasedGTsForEachPhaseID.entrySet()) {
            filteredPhasedCalls.put(pidAndPgts.getKey(), new ImmutablePair<>(vc.getStart(), pidAndPgts.getValue()));
        }
    }

    public void addFirstPassArtifactProbability(final double prob) {
        firstPassArtifactProbabilities.add(prob);
    }

    public void addRealVariantCount(final double x, final boolean isSNV) {
        realVariantCount.add(x);
        (isSNV ? realSNVCount : realIndelCount).add(x);
    }

    public void addTechnicalArtifactCount(final double x) { technicalArtifactCount.add(x); }


    private void adjustThreshold() {
        switch (MTFAC.thresholdStrategy) {
            case CONSTANT:
                artifactProbabilityThreshold = MTFAC.posteriorThreshold;
                break;
            case FALSE_DISCOVERY_RATE:
                artifactProbabilityThreshold = calculateThresholdBasedOnFalseDiscoveryRate(firstPassArtifactProbabilities, MTFAC.maxFalsePositiveRate);
                break;
            case OPTIMAL_F_SCORE:
                artifactProbabilityThreshold = calculateThresholdBasedOnOptimalFScore(firstPassArtifactProbabilities, MTFAC.fScoreBeta);
                break;
            default:
                throw new GATKException.ShouldNeverReachHereException("Invalid threshold strategy type: " + MTFAC.thresholdStrategy + ".");
        }
    }

    /**
     *
     * Compute the filtering threshold that ensures that the false positive rate among the resulting pass variants
     * will not exceed the requested false positive rate
     *
     * @param posteriors A list of posterior probabilities, which gets sorted
     * @param requestedFPR We set the filtering threshold such that the FPR doesn't exceed this value
     * @return
     */
    @VisibleForTesting
    static double calculateThresholdBasedOnFalseDiscoveryRate(final List<Double> posteriors, final double requestedFPR){
        ParamUtils.isPositiveOrZero(requestedFPR, "requested FPR must be non-negative");
        final double thresholdForFilteringNone = 1.0;
        final double thresholdForFilteringAll = 0.0;

        Collections.sort(posteriors);

        final int numPassingVariants = posteriors.size();
        double cumulativeExpectedFPs = 0.0;

        for (int i = 0; i < numPassingVariants; i++){
            final double posterior = posteriors.get(i);

            // One can show that the cumulative error rate is monotonically increasing in i
            final double expectedFPR = (cumulativeExpectedFPs + posterior) / (i + 1);
            if (expectedFPR > requestedFPR){
                return i > 0 ? posteriors.get(i-1) : thresholdForFilteringAll;
            }

            cumulativeExpectedFPs += posterior;
        }

        // If the expected FP rate never exceeded the max tolerable value, then we can let everything pass
        return thresholdForFilteringNone;
    }

    /**
     * Compute the filtering threshold that maximizes the F_beta score
     *
     * @param posteriors A list of posterior probabilities, which gets sorted
     * @param beta relative weight of recall to precision
     */
    @VisibleForTesting
    static double calculateThresholdBasedOnOptimalFScore(final List<Double> posteriors, final double beta){
        ParamUtils.isPositiveOrZero(beta, "requested F-score beta must be non-negative");

        Collections.sort(posteriors);

        final double expectedTruePositives = posteriors.stream()
                .mapToDouble(prob -> 1 - prob).sum();


        // starting from filtering everything (threshold = 0) increase the threshold to maximize the F score
        final MutableDouble truePositives = new MutableDouble(0);
        final MutableDouble falsePositives = new MutableDouble(0);
        final MutableDouble falseNegatives = new MutableDouble(expectedTruePositives);
        int optimalIndexInclusive = -1; // include all indices up to and including this. -1 mean filter all.
        double optimalFScore = 0;   // if you exclude everything, recall is zero

        final int N = posteriors.size();

        for (int n = 0; n < N; n++){
            truePositives.add(1 - posteriors.get(n));
            falsePositives.add(posteriors.get(n));
            falseNegatives.subtract(1 - posteriors.get(n));
            final double F = (1+beta*beta)*truePositives.getValue() /
                    ((1+beta*beta)*truePositives.getValue() + beta*beta*falseNegatives.getValue() + falsePositives.getValue());
            if (F >= optimalFScore) {
                optimalIndexInclusive = n;
                optimalFScore = F;
            }
        }

        return optimalIndexInclusive == -1 ? 0 : (optimalIndexInclusive == N - 1 ? 1 : posteriors.get(optimalIndexInclusive));
    }

    public static boolean hasPhaseInfo(final Genotype genotype) {
        return genotype.hasExtendedAttribute(GATKVCFConstants.HAPLOTYPE_CALLER_PHASING_GT_KEY) && genotype.hasExtendedAttribute(GATKVCFConstants.HAPLOTYPE_CALLER_PHASING_ID_KEY);
    }

    public void accumulateData(final VariantContext vc) {
        // individual filter data
        filters.forEach(f -> f.accumulateDataForLearning(vc, this));

        // data shared by multiple filters
        double artifactProbability = 0;
        double technicalArtifactProbability = 0;

        for (final Mutect2VariantFilter filter : filters) {
            final double prob = filter.artifactProbability(vc, this);
            artifactProbability = Math.max(artifactProbability, prob);
            technicalArtifactProbability = filter.isTechnicalArtifact() ? Math.max(technicalArtifactProbability, prob) : technicalArtifactProbability;
        }

        // TODO: wait -- are artifactProbabilibty and overllAndTechnicalArtifactProbs redundant?
        final Pair<Double, Double> overallAndTechnicalArtifactProbs = overallAndTechnicalOnlyArtifactProbabilities(vc);

        addRealVariantCount(1 - overallAndTechnicalArtifactProbs.getLeft(), vc.isSNP());
        addTechnicalArtifactCount(overallAndTechnicalArtifactProbs.getRight());


        // TODO: could bad haplotypes just be a state of the BadHaplotypeFilter?
        // TODO: could it even be probabilistic based on the artifact probability of the worst call on same haplotype?
        // bad haplotypes and artifact posteriors
        if (overallAndTechnicalArtifactProbs.getLeft() > getArtifactProbabilityThreshold() - EPSILON) {
            recordFilteredHaplotypes(vc);
        }

        addFirstPassArtifactProbability(overallAndTechnicalArtifactProbs.getLeft());
    }

    private Pair<Double, Double> overallAndTechnicalOnlyArtifactProbabilities(final VariantContext vc) {
        return overallAndTechnicalOnlyArtifactProbabilities(filters.stream()
                .collect(Collectors.toMap(f -> f, f -> f.calculateArtifactProbability(vc, this))));
    }

    private Pair<Double, Double> overallAndTechnicalOnlyArtifactProbabilities(final Map<Mutect2VariantFilter, Double> map) {
        final double technicalArtifactProbability = map.entrySet().stream().filter(entry -> entry.getKey().isTechnicalArtifact())
                .mapToDouble(entry -> entry.getValue()).max().orElseGet(() -> 0);
        final double nonTechnicalArtifactProbability = map.entrySet().stream().filter(entry -> !entry.getKey().isTechnicalArtifact())
                .mapToDouble(entry -> entry.getValue()).max().orElseGet(() -> 0);

        final double overallProbability = 1 - (1 - technicalArtifactProbability) * (1 - nonTechnicalArtifactProbability);

        return ImmutablePair.of(overallProbability, technicalArtifactProbability);
    }

    public void learnParameters() {
        // indiividual filter parameters
        filters.forEach(Mutect2VariantFilter::learnParameters);

        // global parameters
        priorProbOfArtifactVersusVariant = (technicalArtifactCount.getValue() + 1) / (realVariantCount.getValue() + technicalArtifactCount.getValue() + 2);
        if (totalCallableSites.isPresent()) {
            log10PriorOfSomaticSNV = Math.log10(realSNVCount.getValue() / totalCallableSites.getAsLong());
            log10PriorOfSomaticIndel = Math.log10(realIndelCount.getValue() / totalCallableSites.getAsLong());
        }

        adjustThreshold();

        // this is crucial -- otherwise nth pass will use duplicate accumulated data from 0th, 1st. . . n-1th pass
        clearAccumulatedData();
    }

    private void clearAccumulatedData() {
        filters.forEach(Mutect2VariantFilter::clearAccumulatedData);

        firstPassArtifactProbabilities.clear();
    }

    public void writeFilteringStats(final File filteringStatsFile) {
        outputStats.writeFilteringStats(filteringStatsFile);
    }

    public VariantContext applyFiltersAndAccumulateOutputStats(final VariantContext vc) {
        final VariantContextBuilder vcb = new VariantContextBuilder(vc).filters(new HashSet<>());

        final Map<Mutect2VariantFilter, Double> artifactProbabilities = filters.stream()
                .collect(Collectors.toMap(f -> f, f -> f.artifactProbability(vc, this)));

        final double overallArtifactProbability = overallAndTechnicalOnlyArtifactProbabilities(artifactProbabilities).getLeft();

        final boolean filtered = overallArtifactProbability > getArtifactProbabilityThreshold() - EPSILON;

        outputStats.recordCall(filtered, overallArtifactProbability, artifactProbabilities);

        for (final Map.Entry<Mutect2VariantFilter, Double> entry : artifactProbabilities.entrySet()) {
            final double artifactProbability = entry.getValue();

            entry.getKey().phredScaledPosteriorAnnotationName().ifPresent(annotation -> {
                if (entry.getKey().requiredAnnotations().stream().allMatch(vc::hasAttribute)) {
                    vcb.attribute(annotation, QualityUtils.errorProbToQual(artifactProbability));
                }
            });

            // TODO: clarify this logic
            if (artifactProbability > EPSILON && artifactProbability > getArtifactProbabilityThreshold() - EPSILON) {
                vcb.filter(entry.getKey().filterName());
            }
        }

        return vcb.make();
    }

    /**
     * Helper class used on the final pass of {@link FilterMutectCalls} to record total expected true positives, false positives,
     * and false negatives, as well as false positives and false negatives attributable to each filter
     */
    private class OutputStats {
        private int pass = 0;
        private double TPs = 0;
        private double FPs = 0;
        private double FNs = 0;

        private Map<Mutect2VariantFilter, MutableDouble> filterFPs = makeEmptyFilterCounts();
        private Map<Mutect2VariantFilter, MutableDouble> filterFNs = makeEmptyFilterCounts();

        public void recordCall(final boolean filtered, final double artifactProbability,
                               final Map<Mutect2VariantFilter, Double> artifactProbabilities) {
            if (filtered) {
                FNs += 1 - artifactProbability;
            } else {
                pass++;
                FPs += artifactProbability;
                TPs += 1 - artifactProbability;
            }

            for (final Map.Entry<Mutect2VariantFilter, Double> entry : artifactProbabilities.entrySet()) {
                final double filterArtifactProbability = entry.getValue();
                if (filterArtifactProbability > EPSILON && filterArtifactProbability > getArtifactProbabilityThreshold() - EPSILON) {
                    filterFNs.get(entry.getKey()).add(1 - artifactProbability);
                } else if (!filtered) {
                    filterFPs.get(entry.getKey()).add(filterArtifactProbability);
                }
            }
        }

        public void writeFilteringStats(final File filteringStatsFile) {
            final double totalTrueVariants = TPs + FNs;

            final List<FilterStats> filterStats = filters.stream()
                    .map(f -> new FilterStats(f.filterName(), filterFPs.get(f).getValue(), filterFPs.get(f).getValue() / pass,
                            filterFNs.get(f).getValue(), filterFNs.get(f).getValue() / totalTrueVariants))
                    .filter(stats -> stats.getFalsePositiveCount() > 0 || stats.getFalseNegativeCount() > 0)
                    .collect(Collectors.toList());

            FilterStats.writeM2FilterSummary(filterStats, filteringStatsFile, getArtifactProbabilityThreshold(), pass, TPs, FPs, FNs);
        }

        private Map<Mutect2VariantFilter, MutableDouble> makeEmptyFilterCounts() {
            return filters.stream().collect(Collectors.toMap(f -> f, f -> new MutableDouble(0)));
        }


        public void clear() {
            pass = 0;
            TPs = 0;
            FPs = 0;
            FNs = 0;

            filterFPs = makeEmptyFilterCounts();
            filterFNs = makeEmptyFilterCounts();
        }
    }

}
