package service

import cats.MonadError
import cats.syntax.apply._
import cats.syntax.flatMap._
import external.library.ParallelEffect
import external.library.syntax.parallelEffect._
import integration.{ CacheIntegration, ProductIntegration, UserIntegration }
import log.effect.LogWriter
import model.DomainModel._

import scala.concurrent.duration._

final case class PriceService[F[_]: ParallelEffect: MonadError[?[_], Throwable]](
  cache: CacheIntegration[F],
  userInt: UserIntegration[F],
  productInt: ProductIntegration[F],
  logger: LogWriter[F],
  productTimeout: FiniteDuration = 8.seconds,
  preferenceTimeout: FiniteDuration = 8.seconds,
  priceTimeout: FiniteDuration = 8.seconds
) {

  /**
    * Going back to ParallelEffect and the fs2 implementation as the new cats.effect version 0.10 changes the semantic
    * of parMapN because of the cancellation. It is not able anymore to collect multiple errors in the resulting
    * MonadError as explained in this gitter conversation
    *
    * https://gitter.im/typelevel/cats-effect?at=5aac5013458cbde55742ef7e
    *
    * While waiting for a different solution with cats.Parallel, this suits the purpose better
    */
  def prices(userId: UserId, productIds: Seq[ProductId]): F[List[Price]] =
    (userFor(userId), productsFor(productIds), preferencesFor(userId))
      .parallelMap(6.seconds)(priceCalculator.finalPrices)
      .flatten

  private def userFor(userId: UserId): F[User] =
    logger.debug(s"Collecting user details for id $userId") >>
      userInt.user(userId) <*
      logger.debug(s"User details collected for id $userId")

  private def preferencesFor(userId: UserId): F[UserPreferences] =
    logger.debug(s"Looking up user preferences for user $userId") >>
      preferenceFetcher.userPreferences(userId) <*
      logger.debug(s"User preferences look up for $userId completed")

  private def productsFor(productIds: Seq[ProductId]): F[List[Product]] =
    logger.debug(s"Collecting product details for products $productIds") >>
      productRepo.storedProducts(productIds) <*
      logger.debug(s"Product details collection for $productIds completed")

  private lazy val preferenceFetcher: PreferenceFetcher[F] =
    PreferenceFetcher(userInt, logger)

  private lazy val productRepo: ProductRepo[F] =
    ProductRepo(cache, productInt, logger, productTimeout)

  private lazy val priceCalculator: PriceCalculator[F] =
    PriceCalculator(productInt, logger, priceTimeout)
}
