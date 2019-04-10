package coop.rchain.casper.genesis.contracts

import cats.effect.{Concurrent, ContextShift, Sync}
import cats.implicits._
import cats.{FlatMap, Parallel}
import coop.rchain.casper.MultiParentCasperTestUtil.createBonds
import coop.rchain.casper.genesis.Genesis
import coop.rchain.casper.protocol.{BlockMessage, DeployData}
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.casper.util.rholang.RuntimeManager
import coop.rchain.crypto.hash.Blake2b512Random
import coop.rchain.crypto.signatures.Ed25519
import coop.rchain.metrics
import coop.rchain.metrics.Metrics
import coop.rchain.models.Par
import coop.rchain.rholang.build.CompiledRholangSource
import coop.rchain.rholang.interpreter.Runtime.SystemProcess
import coop.rchain.rholang.interpreter.accounting.Cost
import coop.rchain.rholang.interpreter.{accounting, ParBuilder, Runtime, TestRuntime}
import coop.rchain.shared.Log
import monix.execution.Scheduler

object TestUtil {

  private val rhoSpecDeploy: DeployData =
    DeployData(
      deployer = ProtoUtil.stringToByteString(
        "4ae94eb0b2d7df529f7ae68863221d5adda402fc54303a3d90a8a7a279326828"
      ),
      timestamp = 1539808849271L,
      term = CompiledRholangSource("RhoSpecContract.rho").code,
      phloLimit = accounting.MAX_VALUE
    )

  def runtime[F[_]: Concurrent: ContextShift, G[_]](
      extraServices: Seq[SystemProcess.Definition[F]] = Seq.empty
  )(implicit scheduler: Scheduler, parallel: Parallel[F, G]): F[Runtime[F]] = {
    implicit val log: Log[F]            = new Log.NOPLog[F]
    implicit val metricsEff: Metrics[F] = new metrics.Metrics.MetricsNOP[F]
    for {
      runtime <- TestRuntime.create[F, G](extraServices)
      _       <- Runtime.injectEmptyRegistryRoot[F](runtime.space, runtime.replaySpace)
    } yield runtime
  }

  def runTestsWithDeploys[F[_]: Concurrent: ContextShift, G[_]: Parallel[F, ?[_]]](
      tests: CompiledRholangSource,
      genesisSetup: RuntimeManager[F] => F[BlockMessage],
      otherLibs: Seq[DeployData],
      additionalSystemProcesses: Seq[SystemProcess.Definition[F]]
  )(
      implicit scheduler: Scheduler
  ): F[Unit] =
    for {
      runtime        <- TestUtil.runtime[F, G](additionalSystemProcesses)
      runtimeManager <- RuntimeManager.fromRuntime(runtime)

      _ <- genesisSetup(runtimeManager)

      _ <- evalDeploy(rhoSpecDeploy, runtime)
      _ <- otherLibs.toList.traverse(evalDeploy(_, runtime))

      // reset the deployParams.userId before executing the test
      // otherwise it'd execute as the deployer of last deployed contract
      _ <- runtime.shortLeashParams.updateParams(old => old.copy(userId = Par()))

      rand = Blake2b512Random(128)
      _    <- eval(tests.code, runtime)(implicitly, implicitly, rand.splitShort(1))
    } yield ()

  def defaultGenesisSetup[F[_]: Concurrent](runtimeManager: RuntimeManager[F]): F[BlockMessage] = {
    val (_, validators) = (1 to 4).map(_ => Ed25519.newKeyPair).unzip
    val bonds           = createBonds(validators)
    val posValidators   = bonds.map(Validator.tupled).toSeq
    val ethAddress      = "0x041e1eec23d118f0c4ffc814d4f415ac3ef3dcff"
    val initBalance     = 37
    val wallet          = PreWallet(ethAddress, initBalance)
    //FIXME / TODO: do in Genesis:
    // - bond deploys for each validator (from their code in Validator)
    // - create vaults for each validator
    // - transfers to each validator's vault based on their bonds
    // - a genesis vault based on the passed public key and initial rev supply
    //   (introduce new fields to Genesis case class)
    // - "PoS and testRev" deploys, and the 'test pos' one
    Genesis.createGenesisBlock(
      runtimeManager,
      Genesis(
        "RhoSpec-shard",
        1,
        wallet :: Nil,
        ProofOfStake(0, Long.MaxValue, bonds.map(Validator.tupled).toSeq),
        false
      )
    )
  }

  private def evalDeploy[F[_]: Sync](
      deploy: DeployData,
      runtime: Runtime[F]
  )(
      implicit scheduler: Scheduler
  ): F[Unit] = {
    val rand: Blake2b512Random = Blake2b512Random(
      DeployData.toByteArray(ProtoUtil.stripDeployData(deploy))
    )
    eval(deploy.term, runtime)(implicitly, implicitly, rand)
  }

  def eval[F[_]: Sync](
      code: String,
      runtime: Runtime[F]
  )(implicit scheduler: Scheduler, rand: Blake2b512Random): F[Unit] =
    ParBuilder[F].buildNormalizedTerm(code) >>= (evalTerm(_, runtime))

  private def evalTerm[F[_]: FlatMap](
      term: Par,
      runtime: Runtime[F]
  )(implicit scheduler: Scheduler, rand: Blake2b512Random): F[Unit] =
    for {
      _ <- runtime.reducer.setPhlo(Cost.UNSAFE_MAX)
      _ <- runtime.reducer.inj(term)
      _ <- runtime.reducer.phlo
    } yield ()
}
