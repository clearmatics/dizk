/* @file
 *****************************************************************************
 * @author     This file is part of zkspark, developed by SCIPR Lab
 *             and contributors (see AUTHORS).
 * @copyright  MIT license (see LICENSE file)
 *****************************************************************************/

package reductions.r1cs_to_qap;

import algebra.fft.DistributedFFT;
import algebra.fft.FFTAuxiliary;
import algebra.fields.AbstractFieldElementExpanded;
import common.MathUtils;
import common.Utils;
import configuration.Configuration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.spark.api.java.JavaPairRDD;
import relations.objects.Assignment;
import relations.objects.LinearTerm;
import relations.qap.QAPRelationRDD;
import relations.qap.QAPWitnessRDD;
import relations.r1cs.R1CSRelationRDD;
import scala.Tuple2;

public class R1CStoQAPRDD {

  /**
   * Instance map for the R1CSRelation-to-QAP reduction followed by evaluation of the resulting QAP
   * instance.
   *
   * <p>Namely, given a R1CSRelation constraint system r1cs and a field element t, construct a QAP
   * instance (evaluated at t) for which: - At := (A_0(t),A_1(t),...,A_m(t)) - Bt :=
   * (B_0(t),B_1(t),...,B_m(t)) - Ct := (C_0(t),C_1(t),...,C_m(t)) - Ht := (1,t,t^2,...,t^n) - Zt :=
   * Z(t) ("vanishing polynomial of a certain set S, evaluated at t") where m = number of variables
   * of the QAP
   */
  public static <FieldT extends AbstractFieldElementExpanded<FieldT>>
      QAPRelationRDD<FieldT> R1CStoQAPRelation(
          final R1CSRelationRDD<FieldT> r1cs, final FieldT t, final Configuration config) {
    final int numPrimary = r1cs.numPrimary();
    final long numVariables = r1cs.numVariables();
    final long numConstraints = r1cs.numConstraints();
    final long domainSize = MathUtils.lowestPowerOfTwo(numConstraints + numPrimary);
    final int numPartitions = config.numPartitions();

    /* Construct in the Lagrange basis. */
    // lagrangeCoeffs = ..., (i, L_i(t)), ...
    final JavaPairRDD<Long, FieldT> lagrangeCoeffs =
        DistributedFFT.lagrangeCoeffs(t, domainSize, config).persist(config.storageLevel());

    // Add and process the constraints, input_i * 0 = 0, to ensure soundness of input consistency.
    // lagrangeIndices = [ numConstraints, numConstraints + 1, ..., numConstraints + numPrimary - 1]
    final List<Long> lagrangeIndices = new ArrayList<>(numPrimary);
    for (long i = numConstraints; i < numConstraints + numPrimary; i++) {
      lagrangeIndices.add(i);
    }

    // coefficientsMap = {
    //   numConstraints: L_{numConstraints}(t),
    //   numConstraints+1: L_{numConstraints+1}(t),
    //   ...
    // }
    final Map<Long, FieldT> coefficientsMap =
        FFTAuxiliary.subsequenceRadix2LagrangeCoefficients(t, domainSize, lagrangeIndices);

    // lagrangeCoefficients = [
    //   (0, L_{numConstraints}(t)),
    //   (1, L_{numConstraints+1}(t)),
    //   ...
    // }
    final List<Tuple2<Long, FieldT>> lagrangeCoefficients = new ArrayList<>(numPrimary);
    for (final Long index : lagrangeIndices) {
      lagrangeCoefficients.add(new Tuple2<>(index - numConstraints, coefficientsMap.get(index)));
    }
    final JavaPairRDD<Long, FieldT> AtCoeffs =
        config.sparkContext().parallelizePairs(lagrangeCoefficients);

    // TODO (howardwu): Replace hardcoded popular constraint indices with list of these indices
    // from R1CSRelationRDD.
    // Add popular constraint indices to the list of lagrange subsequence indices.
    lagrangeIndices.clear();
    lagrangeIndices.add(numConstraints - 1);
    final Map<Long, FieldT> popularCoefficients =
        FFTAuxiliary.subsequenceRadix2LagrangeCoefficients(t, domainSize, lagrangeIndices);
    final JavaPairRDD<Long, FieldT> popularAt =
        r1cs.constraints()
            .A()
            .filter(e -> e._1.equals(numConstraints - 1))
            .values()
            .mapToPair(
                term -> {
                  return new Tuple2<>(
                      term.index(), term.value().mul(popularCoefficients.get(numConstraints - 1)));
                })
            .reduceByKey(FieldT::add);

    final JavaPairRDD<Long, FieldT> popularBt =
        r1cs.constraints()
            .B()
            .filter(e -> e._1.equals(numConstraints - 1))
            .values()
            .mapToPair(
                term -> {
                  return new Tuple2<>(
                      term.index(), term.value().mul(popularCoefficients.get(numConstraints - 1)));
                })
            .reduceByKey(FieldT::add);

    final JavaPairRDD<Long, FieldT> popularCt =
        r1cs.constraints()
            .C()
            .filter(e -> e._1.equals(numConstraints - 1))
            .values()
            .mapToPair(
                term -> {
                  return new Tuple2<>(
                      term.index(), term.value().mul(popularCoefficients.get(numConstraints - 1)));
                })
            .reduceByKey(FieldT::add);

    /* Process all other constraints; evaluate A(t), B(t), and C(t). */
    final JavaPairRDD<Long, FieldT> At =
        r1cs.constraints()
            .A()
            .filter(e -> !e._1.equals(numConstraints - 1))
            .join(lagrangeCoeffs)
            .mapToPair(
                term -> {
                  // Multiply the constraint value by the basis coefficient value.
                  return new Tuple2<>(term._2._1.index(), term._2._1.value().mul(term._2._2));
                })
            .union(popularAt)
            .union(AtCoeffs)
            .reduceByKey(FieldT::add, numPartitions)
            .persist(config.storageLevel());

    final JavaPairRDD<Long, FieldT> Bt =
        r1cs.constraints()
            .B()
            .filter(e -> !e._1.equals(numConstraints - 1))
            .join(lagrangeCoeffs)
            .mapToPair(
                term -> {
                  // Multiply the constraint value by the basis coefficient value.
                  return new Tuple2<>(term._2._1.index(), term._2._1.value().mul(term._2._2));
                })
            .union(popularBt)
            .reduceByKey(FieldT::add, numPartitions)
            .persist(config.storageLevel());

    final JavaPairRDD<Long, FieldT> Ct =
        r1cs.constraints()
            .C()
            .filter(e -> !e._1.equals(numConstraints - 1))
            .join(lagrangeCoeffs)
            .mapToPair(
                term -> {
                  // Multiply the constraint value by the basis coefficient value.
                  return new Tuple2<>(term._2._1.index(), term._2._1.value().mul(term._2._2));
                })
            .union(popularCt)
            .reduceByKey(FieldT::add, numPartitions)
            .persist(config.storageLevel());

    // Compute H(t).
    final JavaPairRDD<Long, FieldT> Ht =
        Utils.fillRDD(domainSize + 1, t.zero(), config)
            .mapToPair(term -> new Tuple2<>(term._1, t.pow(term._1)));

    // Compute vanishing polynomial at t.
    final FieldT Zt = DistributedFFT.computeZ(t, domainSize);

    return new QAPRelationRDD<>(At, Bt, Ct, Ht, Zt, t, numPrimary, numVariables, domainSize);
  }

  /**
   * Witness map for the R1CSRelation-to-QAP reduction.
   *
   * <p>More precisely, compute the coefficients h_0,h_1,...,h_n of the polynomial H(z) :=
   * (A(z)*B(z)-C(z))/Z(z) where: A(z) := A_0(z) + \sum_{k=1}^{m} w_k A_k(z) + d1 * Z(z), B(z) :=
   * B_0(z) + \sum_{k=1}^{m} w_k B_k(z) + d2 * Z(z), C(z) := C_0(z) + \sum_{k=1}^{m} w_k C_k(z) + d3
   * * Z(z), Z(z) := "vanishing polynomial of set S" and m = number of variables of the QAP n =
   * degree of the QAP
   *
   * <p>This is done as follows: (1) compute evaluations of A,B,C on S = {sigma_1,...,sigma_n} (2)
   * compute coefficients of A,B,C (3) compute evaluations of A,B,C on T = "coset of S" (4) compute
   * evaluation of H on T (5) compute coefficients of H
   *
   * <p>The code below is not as simple as the above high-level description due to some reshuffling
   * to save space.
   */
  public static <FieldT extends AbstractFieldElementExpanded<FieldT>>
      QAPWitnessRDD<FieldT> R1CStoQAPWitness(
          final R1CSRelationRDD<FieldT> r1cs,
          final Assignment<FieldT> primary,
          final JavaPairRDD<Long, FieldT> fullAssignment,
          final FieldT fieldFactory,
          final Configuration config) {

    if (config.debugFlag()) {
      assert (r1cs.isSatisfied(primary, fullAssignment));
    }

    final FieldT multiplicativeGenerator = fieldFactory.multiplicativeGenerator();
    final FieldT zero = fieldFactory.zero();
    // We do a Radix2-FFT to retrieve the QAP (polynomial form) from the R1CS (matrix form)
    // via interpolation on a given domain of size a "big enough" power of 2. Add space for
    // consistency checks for all primary inputs, including the 1 constant.
    final long domainSize = MathUtils.lowestPowerOfTwo(r1cs.numConstraints() + r1cs.numPrimary());

    config.beginLog("Account for the additional constraints input_i * 0 = 0.");
    final List<Tuple2<Long, FieldT>> shiftedPrimary = new ArrayList<>();
    for (int i = 0; i < r1cs.numPrimary(); i++) {
      shiftedPrimary.add(new Tuple2<>(r1cs.numConstraints() + i, primary.get(i)));
    }
    // Elements (numConstraints + i, var[i])
    final JavaPairRDD<Long, FieldT> additionalA =
        config.sparkContext().parallelizePairs(shiftedPrimary, config.numPartitions());
    config.endLog("Account for the additional constraints input_i * 0 = 0.");

    // TODO (howardwu): Replace hardcoded popular variable assignment indices with list of
    // these indices from R1CSRelationRDD.
    config.beginLog("Compute evaluation of polynomials A, B, and C, on set S.");
    // r1cs.constraints() returns a `R1CSConstraintsRDD` which is a set of `JavaPairRDD<Long,
    // LinearTerm<FieldT>>`.
    // In other, words, A, B, C are all vectors of linear terms (i.e. matrices that will be
    // intepolated on the chosen domain)

    // For each constraint term, (constraint_idx, (var_idx, coeff)) with var_idx == 0, map to
    // (constraint_idx, coeff) and combine (sum) per index.
    final JavaPairRDD<Long, FieldT> zeroIndexedA =
        r1cs.constraints()
            .A()
            .filter(e -> e._2.index() == 0)
            .mapValues(LinearTerm::value)
            .reduceByKey(FieldT::add);

    final JavaPairRDD<Long, FieldT> zeroIndexedB =
        r1cs.constraints()
            .B()
            .filter(e -> e._2.index() == 0)
            .mapValues(LinearTerm::value)
            .reduceByKey(FieldT::add);

    final JavaPairRDD<Long, FieldT> zeroIndexedC =
        r1cs.constraints()
            .C()
            .filter(e -> e._2.index() == 0)
            .mapValues(LinearTerm::value)
            .reduceByKey(FieldT::add);

    // For each term (constraint_idx, (var_idx, coeff)) with var_idx != 0,
    //   map to (var_idx, (constraint_idx, coeff)
    //   join with assignment: (var_idx, var_value) to get (var_idx, ((constraint_idx, coeff),
    // var_value))
    //   map to (constrain_idx, coeff * var_value)
    //   union with additionalA
    //   union with zeroIndexedA
    //   combine common keys
    JavaPairRDD<Long, FieldT> A =
        r1cs.constraints()
            .A()
            .filter(e -> e._2.index() != 0)
            .mapToPair(
                term -> {
                  // Swap the constraint and term index positions.
                  return new Tuple2<>(term._2.index(), new Tuple2<>(term._1, term._2.value()));
                })
            .join(fullAssignment, config.numPartitions())
            .mapToPair(
                term -> {
                  // Multiply the constraint value by the input value.
                  return new Tuple2<>(term._2._1._1, term._2._1._2.mul(term._2._2));
                })
            .union(additionalA)
            .union(zeroIndexedA)
            .reduceByKey(FieldT::add);

    JavaPairRDD<Long, FieldT> B =
        r1cs.constraints()
            .B()
            .filter(e -> e._2.index() != 0)
            .mapToPair(
                term -> {
                  // Swap the constraint and term index positions.
                  return new Tuple2<>(term._2.index(), new Tuple2<>(term._1, term._2.value()));
                })
            .join(fullAssignment, config.numPartitions())
            .mapToPair(
                term -> {
                  // Multiply the constraint value by the input value.
                  return new Tuple2<>(term._2._1._1, term._2._1._2.mul(term._2._2));
                })
            .union(zeroIndexedB)
            .reduceByKey(FieldT::add);

    JavaPairRDD<Long, FieldT> C =
        r1cs.constraints()
            .C()
            .filter(e -> e._2.index() != 0)
            .mapToPair(
                term -> {
                  // Swap the constraint and term index positions.
                  return new Tuple2<>(term._2.index(), new Tuple2<>(term._1, term._2.value()));
                })
            .join(fullAssignment, config.numPartitions())
            .mapToPair(
                term -> {
                  // Multiply the constraint value by the input value.
                  return new Tuple2<>(term._2._1._1, term._2._1._2.mul(term._2._2));
                })
            .union(zeroIndexedC)
            .reduceByKey(FieldT::add);
    config.endLog("Compute evaluation of polynomials A, B, and C, on set S.");

    final int rows = (int) MathUtils.lowestPowerOfTwo((long) Math.sqrt(domainSize));
    final int cols = (int) (domainSize / rows);

    config.beginLog("Perform radix-2 inverse FFT to determine the coefficients of A, B, and C.");
    A = DistributedFFT.radix2InverseFFT(A, rows, cols, fieldFactory);
    B = DistributedFFT.radix2InverseFFT(B, rows, cols, fieldFactory);
    C = DistributedFFT.radix2InverseFFT(C, rows, cols, fieldFactory);
    config.endLog("Perform radix-2 inverse FFT to determine the coefficients of A, B, and C.");

    config.beginLog("Compute evaluation of polynomials A, B, and C on set T.");
    A = DistributedFFT.radix2CosetFFT(A, multiplicativeGenerator, rows, cols, fieldFactory);
    B = DistributedFFT.radix2CosetFFT(B, multiplicativeGenerator, rows, cols, fieldFactory);
    C = DistributedFFT.radix2CosetFFT(C, multiplicativeGenerator, rows, cols, fieldFactory);
    config.endLog("Compute evaluation of polynomials A, B, and C on set T.");

    config.beginLog("Compute the evaluation, (A * B) - C, for polynomial H on a set T.");
    JavaPairRDD<Long, FieldT> coefficientsH =
        A.join(B)
            .join(C, config.numPartitions())
            .mapValues(element -> element._1._1.mul(element._1._2).sub(element._2));
    config.endLog("Compute the evaluation, (A * B) - C, for polynomial H on a set T.");

    config.beginLog("Divide by Z on set T.");
    coefficientsH =
        DistributedFFT.divideByZOnCoset(multiplicativeGenerator, coefficientsH, domainSize);
    config.endLog("Divide by Z on set T.");

    config.beginLog("Compute coefficients of polynomial H.");
    coefficientsH =
        DistributedFFT.radix2CosetInverseFFT(
                coefficientsH, multiplicativeGenerator, rows, cols, fieldFactory)
            .union(
                config
                    .sparkContext()
                    .parallelizePairs(Collections.singletonList(new Tuple2<>(domainSize, zero))));
    config.endLog("Compute coefficients of polynomial H.");

    return new QAPWitnessRDD<>(
        fullAssignment, coefficientsH, r1cs.numPrimary(), r1cs.numVariables(), domainSize);
  }
}
