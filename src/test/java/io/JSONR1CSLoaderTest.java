package io;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import algebra.curves.barreto_naehrig.bn254a.BN254aFields.BN254aFr;
import configuration.Configuration;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.storage.StorageLevel;
import org.json.simple.parser.ParseException;
import org.junit.jupiter.api.Test;
import profiler.utils.SparkUtils;
import relations.objects.Assignment;
import relations.r1cs.R1CSRelation;
import relations.r1cs.R1CSRelationRDD;
import scala.Tuple2;

public class JSONR1CSLoaderTest {

  @Test
  public void loadSerialTest() {
    JSONR1CSLoader loader = getSimpleCircuitLoader();

    // Load the test data
    R1CSRelation<BN254aFr> loadedRelation = loader.loadSerial(BN254aFr.ONE, BN254aFr.FrParameters);
    assertTrue(loadedRelation.isValid());

    // Make sure the loaded relation is satisfied with a VALID assignment
    Assignment<BN254aFr> primary = new Assignment<BN254aFr>();
    // Allocate ONE - needs to be done manually (as opposed to how things are done in libsnark)
    // see further discussion in the `evaluate` function in `LinearCombination.java`
    primary.add(BN254aFr.ONE);
    primary.add(new BN254aFr("12"));
    Assignment<BN254aFr> auxiliary = new Assignment<BN254aFr>();
    auxiliary.add(new BN254aFr("1"));
    auxiliary.add(new BN254aFr("1"));
    auxiliary.add(new BN254aFr("1"));
    assertTrue(loadedRelation.isSatisfied(primary, auxiliary));

    // Make sure the loaded relation is NOT satisfied with an INVALID assignment
    Assignment<BN254aFr> invalidPrimary = new Assignment<BN254aFr>();
    invalidPrimary.add(BN254aFr.ONE);
    invalidPrimary.add(new BN254aFr("12"));
    Assignment<BN254aFr> invalidAuxiliary = new Assignment<BN254aFr>();
    invalidAuxiliary.add(new BN254aFr("2"));
    invalidAuxiliary.add(new BN254aFr("1"));
    invalidAuxiliary.add(new BN254aFr("1"));
    assertFalse(loadedRelation.isSatisfied(invalidPrimary, invalidAuxiliary));
  }

  @Test
  public void loadRDDTest() {
    JSONR1CSLoader loader = getSimpleCircuitLoader();

    // Set up configuration and SPARK context
    final SparkConf conf = new SparkConf().setMaster("local").setAppName("loader");
    conf.set("spark.files.overwrite", "true");
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer");
    conf.registerKryoClasses(SparkUtils.zksparkClasses());

    final JavaSparkContext sc;
    final Configuration config;
    sc = new JavaSparkContext(conf);
    config = new Configuration(1, 1, 1, 2, sc, StorageLevel.MEMORY_ONLY());
    config.setRuntimeFlag(false);
    config.setDebugFlag(true);

    try {
      R1CSRelationRDD<BN254aFr> loadedRelationRDD =
          loader.loadRDD(BN254aFr.ONE, BN254aFr.FrParameters, config);
      assertTrue(loadedRelationRDD.isValid());

      // Make sure the loaded relation is satisfied with a VALID assignment
      Assignment<BN254aFr> primary = new Assignment<BN254aFr>();
      // Allocate ONE - needs to be done manually (as opposed to how things are done in libsnark)
      // see further discussion in the `evaluate` function in `LinearCombination.java`
      primary.add(BN254aFr.ONE);
      primary.add(new BN254aFr("12"));

      List<Tuple2<Long, BN254aFr>> fullAssignment =
          Arrays.asList(
              new Tuple2<>((long) 0, BN254aFr.ONE), // Primary
              new Tuple2<>((long) 1, new BN254aFr("12")),
              new Tuple2<>((long) 2, new BN254aFr("1")), // Auxiliary
              new Tuple2<>((long) 3, new BN254aFr("1")),
              new Tuple2<>((long) 4, new BN254aFr("1")));
      JavaRDD<Tuple2<Long, BN254aFr>> assignmentRDD = sc.parallelize(fullAssignment);
      JavaPairRDD<Long, BN254aFr> pairAssignmentRDD =
          JavaPairRDD.<Long, BN254aFr>fromJavaRDD(assignmentRDD);

      boolean result = loadedRelationRDD.isSatisfied(primary, pairAssignmentRDD);
      System.out.println("==========> Result after assignment: " + result);
      assertTrue(result);

      // Make sure the loaded relation is NOT satisfied with an INVALID assignment
      Assignment<BN254aFr> primaryInvalid = new Assignment<BN254aFr>();
      // Allocate ONE - needs to be done manually (as opposed to how things are done in libsnark)
      // see further discussion in the `evaluate` function in `LinearCombination.java`
      primaryInvalid.add(BN254aFr.ONE);
      primaryInvalid.add(new BN254aFr("12"));

      List<Tuple2<Long, BN254aFr>> fullAssignmentInvalid =
          Arrays.asList(
              new Tuple2<>((long) 0, BN254aFr.ONE), // Primary
              new Tuple2<>((long) 1, new BN254aFr("12")),
              new Tuple2<>((long) 2, new BN254aFr("2")), // Invalid Auxiliary
              new Tuple2<>((long) 3, new BN254aFr("1")),
              new Tuple2<>((long) 4, new BN254aFr("1")));
      JavaPairRDD<Long, BN254aFr> invalidPairAssignmentRDD =
          JavaPairRDD.<Long, BN254aFr>fromJavaRDD(invalidAssignmentRDD);
          sc.parallelizePairs(fullAssignmentInvalid);

      boolean invalidResult =
          loadedRelationRDD.isSatisfied(primaryInvalid, invalidPairAssignmentRDD);
      System.out.println("==========> Result after INVALID assignment: " + invalidResult);
      assertFalse(invalidResult);
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (ParseException e) {
      e.printStackTrace();
    }
  }

  JSONR1CSLoader getSimpleCircuitLoader() {
    final Path path =
        Paths.get(getClass().getClassLoader().getResource("simple_circuit_r1cs.json").getPath());
    if (!Files.exists(path)) {
      fail("Test r1cs file not found: " + path.toString());
    }

    return new JSONR1CSLoader(path.toString());
  }
}
