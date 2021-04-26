package prover;

import static algebra.curves.barreto_naehrig.bn254a.BN254aFields.BN254aFr;

import algebra.curves.AbstractG1;
import algebra.curves.AbstractG2;
import algebra.curves.barreto_lynn_scott.bls12_377.BLS12_377BinaryReader;
import algebra.curves.barreto_lynn_scott.bls12_377.BLS12_377BinaryWriter;
import algebra.curves.barreto_lynn_scott.bls12_377.BLS12_377Fields.BLS12_377Fr;
import algebra.curves.barreto_lynn_scott.bls12_377.BLS12_377G1;
import algebra.curves.barreto_lynn_scott.bls12_377.BLS12_377G2;
import algebra.curves.barreto_naehrig.bn254a.BN254aBinaryReader;
import algebra.curves.barreto_naehrig.bn254a.BN254aBinaryWriter;
import algebra.curves.barreto_naehrig.bn254a.BN254aG1;
import algebra.curves.barreto_naehrig.bn254a.BN254aG2;
import algebra.fields.AbstractFieldElementExpanded;
import configuration.Configuration;
import io.AssignmentReader;
// import profiler.utils.SparkUtils;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import org.apache.commons.cli.*;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.storage.StorageLevel;
import scala.Tuple2;
import zk_proof_systems.zkSNARK.grothBGM17.DistributedProver;
import zk_proof_systems.zkSNARK.grothBGM17.ZKSnarkObjectReader;
import zk_proof_systems.zkSNARK.grothBGM17.ZKSnarkObjectWriter;

public class Prover {
  public static void main(String[] args) throws IOException {
    System.out.println("Distributed Prover (PoC implementation)");

    var options = new Options();
    options.addOption(new Option("h", "help", false, "Display this message"));
    options.addOption(new Option("l", "local", false, "Run on local simulated cluster"));
    options.addOption(new Option("t", "test", false, "Run trivial test to verify setup"));
    options.addOption(new Option("p", "primary-size", true, "Size of primary input (1)"));
    options.addOption(new Option("o", "output", true, "Output file (proof.bin)"));
    options.addOption(new Option("c", "curve", true, "Curve name: bn254a or bls12-377 (bn254a)"));

    try {
      var parser = new BasicParser();
      var cmdLine = parser.parse(options, args);
      var trailing = cmdLine.getArgs();
      System.out.println("trailing: " + String.join(", ", trailing));

      if (cmdLine.hasOption("help")) {
        print_usage(options);
        return;
      }

      if (cmdLine.hasOption("test")) {
        runTest(cmdLine.hasOption("local"));
        return;
      }

      final int primaryInputSize = Integer.parseInt(cmdLine.getOptionValue("primary-size", "1"));
      final String outputFile = cmdLine.getOptionValue("output", "proof.bin");

      // Extract command line arguments and call run.
      if (trailing.length != 2) {
        System.err.println("error: invalid number of arguments\n");
        print_usage(options);
        System.exit(1);
      }

      final String curve = cmdLine.getOptionValue("curve", "bn254a");
      switch (curve) {
        case "bn254a":
          runBN254a(
              primaryInputSize, trailing[0], trailing[1], outputFile, cmdLine.hasOption("local"));
          break;
        case "bls12-377":
          runBLS12_377(
              primaryInputSize, trailing[0], trailing[1], outputFile, cmdLine.hasOption("local"));
          break;
        default:
          throw new ParseException("invalid curve: " + curve);
      }

    } catch (ParseException e) {
      System.err.println("error: " + e.getMessage());
    }
  }

  static void print_usage(Options options) {
    new HelpFormatter().printHelp("prover <PROVING-KEY-FILE> <ASSIGNMENT-FILE>", options);
  }

  static JavaSparkContext createSparkContext(boolean local) {
    final var sessionBuilder = SparkSession.builder().appName("prover");

    if (local) {
      sessionBuilder.master("local");
    }

    final SparkSession spark = sessionBuilder.getOrCreate();

    spark.sparkContext().conf().set("spark.files.overwrite", "true");
    // TODO: reinstate this when it can be made to work
    //   spark.sparkContext().conf().set(
    //     "spark.serializer",
    //     "org.apache.spark.serializer.KryoSerializer");
    //   spark.sparkContext().conf().registerKryoClasses(SparkUtils.zksparkClasses());
    return new JavaSparkContext(spark.sparkContext());
  }

  static void runTest(boolean local) throws IOException {
    var sc = createSparkContext(local);

    final int numPartitions = 64;
    final long numGroups = 16;
    final int numValues = 1024;

    var values = new ArrayList<Tuple2<Long, Long>>(numValues);
    for (long i = 0; i < numValues; ++i) {
      values.add(new Tuple2<Long, Long>(i % numGroups, i));
    }

    final Function2<Long, Long, Long> reduceSum =
        (x, y) -> {
          return x + y;
        };

    final var pairsRDD = sc.parallelizePairs(values, numPartitions);
    final var reducedRDD = pairsRDD.reduceByKey(reduceSum, numPartitions);
    final var reduced = reducedRDD.collect();

    // Check by summing everything
    final long expect = ((numValues - 1) * numValues) / 2;
    long sum = 0;
    for (var tuple : reduced) {
      sum = sum + tuple._2;
    }
    if (expect != sum) {
      System.out.println("reduced: " + String.valueOf(reduced));
      throw new RuntimeException(
          "expected " + String.valueOf(expect) + ", saw " + String.valueOf(sum));
    }

    System.out.println("TEST PASSED");
    sc.stop();
  }

  static void runBN254a(
      final int primaryInputSize,
      final String provingKeyFile,
      final String assignmentFile,
      final String outputFile,
      final boolean local)
      throws IOException {

    System.out.println(" provingKeyFile: " + provingKeyFile);
    System.out.println(" assignmentFile: " + assignmentFile);
    System.out.println(" outputFile: " + outputFile);

    var provingKeyReader =
        new ZKSnarkObjectReader<BN254aFr, BN254aG1, BN254aG2>(
            new BN254aBinaryReader(new FileInputStream(provingKeyFile)));
    var assignmentReader =
        new AssignmentReader<BN254aFr, BN254aG1, BN254aG2>(
            new BN254aBinaryReader(new FileInputStream(assignmentFile)));
    var proofWriter =
        new ZKSnarkObjectWriter<BN254aFr, BN254aG1, BN254aG2>(
            new BN254aBinaryWriter(new FileOutputStream(outputFile)));

    Prover.<BN254aFr, BN254aG1, BN254aG2>run(
        primaryInputSize, provingKeyReader, assignmentReader, proofWriter, local, BN254aFr.ONE);
  }

  static void runBLS12_377(
      final int primaryInputSize,
      final String provingKeyFile,
      final String assignmentFile,
      final String outputFile,
      final boolean local)
      throws IOException {

    System.out.println(" provingKeyFile: " + provingKeyFile);
    System.out.println(" assignmentFile: " + assignmentFile);
    System.out.println(" outputFile: " + outputFile);

    var provingKeyReader =
        new ZKSnarkObjectReader<BLS12_377Fr, BLS12_377G1, BLS12_377G2>(
            new BLS12_377BinaryReader(new FileInputStream(provingKeyFile)));
    var assignmentReader =
        new AssignmentReader<BLS12_377Fr, BLS12_377G1, BLS12_377G2>(
            new BLS12_377BinaryReader(new FileInputStream(assignmentFile)));
    var proofWriter =
        new ZKSnarkObjectWriter<BLS12_377Fr, BLS12_377G1, BLS12_377G2>(
            new BLS12_377BinaryWriter(new FileOutputStream(outputFile)));

    Prover.<BLS12_377Fr, BLS12_377G1, BLS12_377G2>run(
        primaryInputSize, provingKeyReader, assignmentReader, proofWriter, local, BLS12_377Fr.ONE);
  }

  static <
          FrT extends AbstractFieldElementExpanded<FrT>,
          G1T extends AbstractG1<G1T>,
          G2T extends AbstractG2<G2T>>
      void run(
          final int primaryInputSize,
          final ZKSnarkObjectReader<FrT, G1T, G2T> provingKeyReader,
          final AssignmentReader<FrT, G1T, G2T> assignmentReader,
          final ZKSnarkObjectWriter<FrT, G1T, G2T> proofWriter,
          final boolean local,
          final FrT oneFr)
          throws IOException {
    var sc = createSparkContext(local);

    // TODO: make these configurable.
    final int numExecutors = 16;
    final int numCores = 2;
    final int numMemory = 16;
    final int numPartitions = 64;
    final StorageLevel storageLevel = StorageLevel.MEMORY_AND_DISK_SER();

    final int batchSize = 1024;
    final var provingKeyRDD =
        provingKeyReader.readProvingKeyRDD(primaryInputSize, sc, numPartitions, batchSize);
    final var primFullRDD =
        assignmentReader.readPrimaryFullRDD(primaryInputSize, oneFr, sc, numPartitions, batchSize);

    if (!provingKeyRDD.r1cs().isSatisfied(primFullRDD._1, primFullRDD._2)) {
      throw new RuntimeException("assignment does not satisfy r1cs");
    }

    final var config =
        new Configuration(numExecutors, numCores, numMemory, numPartitions, sc, storageLevel);

    final var proof =
        DistributedProver.prove(provingKeyRDD, primFullRDD._1, primFullRDD._2, oneFr, config);
    sc.stop();

    proofWriter.writeProof(proof);
  }
}