/*
 * Copyright 2018 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package controllers

import javax.inject.Inject

import config.ConfigDecorator
import connectors.{FrontEndDelegationConnector, PertaxAuditConnector, PertaxAuthConnector}
import controllers.auth.{AuthorisedActions, LocalPageVisibilityPredicateFactory, PertaxRegime}
import controllers.bindable.{Origin, StrictContinueUrl}
import controllers.helpers.{HomeCardGenerator, HomePageCachingHelper, PaperlessInterruptHelper}
import error.LocalErrorHandler
import models._
import play.api.Logger
import play.api.i18n.MessagesApi
import play.api.mvc._
import play.twirl.api.Html
import services._
import services.partials.{CspPartialService, MessageFrontendService}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.renderer.ActiveTabHome
import uk.gov.hmrc.time.TaxYearResolver
import util.AuditServiceTools._
import util.{DateTimeTools, LocalPartialRetriever}

import scala.concurrent.Future


class ApplicationController @Inject() (
  val messagesApi: MessagesApi,
  val citizenDetailsService: CitizenDetailsService,
  val preferencesFrontendService: PreferencesFrontendService,
  val taiService: TaiService,
  val identityVerificationFrontendService: IdentityVerificationFrontendService,
  val taxCalculationService: TaxCalculationService,
  val selfAssessmentService: SelfAssessmentService,
  val cspPartialService: CspPartialService,
  val userDetailsService: UserDetailsService,
  val messageFrontendService: MessageFrontendService,
  val delegationConnector: FrontEndDelegationConnector,
  val auditConnector: PertaxAuditConnector,
  val authConnector: PertaxAuthConnector,
  val localPageVisibilityPredicateFactory: LocalPageVisibilityPredicateFactory,
  val partialRetriever: LocalPartialRetriever,
  val configDecorator: ConfigDecorator,
  val pertaxRegime: PertaxRegime,
  val localErrorHandler: LocalErrorHandler,
  val homeCardGenerator: HomeCardGenerator,
  val homePageCachingHelper: HomePageCachingHelper,
  val taxCalculationStateFactory: TaxCalculationStateFactory
) extends PertaxBaseController with AuthorisedActions with PaperlessInterruptHelper {

  def index: Action[AnyContent] = VerifiedAction(Nil, activeTab = Some(ActiveTabHome)) {
    implicit pertaxContext =>

      val year = TaxYearResolver.currentTaxYear

      val userAndNino = for( u <- pertaxContext.user; n <- u.nino) yield (u, n)

      val serviceCallResponses = userAndNino.fold[Future[(Option[TaxSummary],Option[TaxCalculationState])]](Future.successful( (None, None) )) { userAndNino =>

        val (user, nino) = userAndNino

        val taxCalculationState: Future[Option[TaxCalculationState]] = if (configDecorator.taxcalcEnabled) {
          taxCalculationService.getTaxCalculation(nino, year - 1) map {
            case TaxCalculationSuccessResponse(taxCalc) => {
              Some(taxCalculationStateFactory.buildFromTaxCalculation(Some(taxCalc)))
            }
            case _ => None
          }
        } else {
          Future.successful(Some(TaxCalculationDisabledState(year - 1, year)))
        }

        val taxSummary: Future[Option[TaxSummary]] = if (configDecorator.taxSummaryEnabled) {
          taiService.taxSummary(nino, year) map {
            case TaxSummarySuccessResponse(ts) => Some(ts)
            case _ => None
          }
        } else {
          Future.successful(None)
        }

        for {
          taxCalculationState <- taxCalculationState
          taxSummary <- taxSummary
        } yield (taxSummary, taxCalculationState)
      }

      val saUserType: Future[SelfAssessmentUserType] = selfAssessmentService.getSelfAssessmentUserType(pertaxContext.authContext)

      val showUserResearchBanner: Future[Boolean] =
        configDecorator.urLinkUrl.fold(Future.successful(false))(_ => homePageCachingHelper.hasUserDismissedUrInvitation.map(!_))


      showUserResearchBanner flatMap { showUserResearchBanner =>
        enforcePaperlessPreference {
          for {
            (taxSummary, taxCalculationState) <- serviceCallResponses
            saUserType <- saUserType
          } yield {

            val incomeCards: Seq[Html] = homeCardGenerator.getIncomeCards(
              pertaxContext.user, taxSummary, taxCalculationState, saUserType)

            val benefitCards: Seq[Html] = homeCardGenerator.getBenefitCards(taxSummary)

            val pensionCards: Seq[Html] = homeCardGenerator.getPensionCards(pertaxContext.user)

            Ok(views.html.home(incomeCards, benefitCards, pensionCards, showUserResearchBanner))
          }
        }
      }
  }

  def uplift(redirectUrl: Option[StrictContinueUrl]): Action[AnyContent] = {
    val pvp = localPageVisibilityPredicateFactory.build(redirectUrl, configDecorator.defaultOrigin)

    AuthorisedFor(pertaxRegime, pageVisibility = pvp).async {
      implicit authContext =>
        implicit request =>
          Future.successful(Redirect(redirectUrl.map(_.url).getOrElse(routes.ApplicationController.index().url)))
    }
  }

  def showUpliftJourneyOutcome(continueUrl: Option[StrictContinueUrl]): Action[AnyContent] = AuthorisedAction() {
    implicit pertaxContext =>

      import IdentityVerificationSuccessResponse._

      //Will be populated if we arrived here because of an IV success/failure
      val journeyId = List(pertaxContext.request.getQueryString("token"), pertaxContext.request.getQueryString("journeyId")).flatten.headOption

      val retryUrl = controllers.routes.ApplicationController.uplift(continueUrl).url

      lazy val allowContinue = configDecorator.allowSaPreview && pertaxContext.user.map(_.isSa).getOrElse(false)

      if (configDecorator.allowLowConfidenceSAEnabled) {
        Future.successful(Redirect(controllers.routes.ApplicationController.ivExemptLandingPage(continueUrl)))
      }
      else {
        journeyId match {
          case Some(jid) =>
            identityVerificationFrontendService.getIVJourneyStatus(jid).map { response =>

              response match {
                case IdentityVerificationSuccessResponse(InsufficientEvidence) => Redirect(controllers.routes.ApplicationController.ivExemptLandingPage(continueUrl))
                case IdentityVerificationSuccessResponse(UserAborted) => Unauthorized(views.html.iv.failure.cantConfirmIdentity(retryUrl))
                case IdentityVerificationSuccessResponse(FailedMatching) => Unauthorized(views.html.iv.failure.cantConfirmIdentity(retryUrl))
                case IdentityVerificationSuccessResponse(Incomplete) => Unauthorized(views.html.iv.failure.failedIvIncomplete(retryUrl))
                case IdentityVerificationSuccessResponse(PrecondFailed) => Unauthorized(views.html.iv.failure.cantConfirmIdentity(retryUrl))
                case IdentityVerificationSuccessResponse(LockedOut) => Unauthorized(views.html.iv.failure.lockedOut(allowContinue))
                case IdentityVerificationSuccessResponse(Success) => Ok(views.html.iv.success.success(continueUrl.map(_.url).getOrElse(routes.ApplicationController.index().url)))
                case IdentityVerificationSuccessResponse(Timeout) => InternalServerError(views.html.iv.failure.timeOut(retryUrl))
                case IdentityVerificationSuccessResponse(TechnicalIssue) =>
                  Logger.warn(s"TechnicalIssue response from identityVerificationFrontendService")
                  InternalServerError(views.html.iv.failure.technicalIssues(retryUrl))
                case r =>
                  Logger.error(s"Unhandled response from identityVerificationFrontendService: $r")
                  InternalServerError(views.html.iv.failure.technicalIssues(retryUrl))
              }
            }
          case None =>
            Logger.error(s"No journeyId present when displaying IV uplift journey outcome")
            Future.successful(BadRequest(views.html.iv.failure.technicalIssues(retryUrl)))
        }
      }
  }

  def signout(continueUrl: Option[StrictContinueUrl], origin: Option[Origin]): Action[AnyContent] = AuthorisedAction(fetchPersonDetails = false) {
    implicit pertaxContext =>
      Future.successful {
        continueUrl.map(_.url).orElse(origin.map(configDecorator.getFeedbackSurveyUrl)).fold(BadRequest("Missing origin")) { url: String =>
            pertaxContext.user match {
              case Some(user) if user.isGovernmentGateway =>
                Redirect(configDecorator.getCompanyAuthFrontendSignOutUrl(url))
              case _ =>
                Redirect(configDecorator.citizenAuthFrontendSignOut).withSession("postLogoutPage" -> url)
            }
        }
      }
  }

  def handleSelfAssessment = VerifiedAction(baseBreadcrumb) {
    implicit pertaxContext =>
      enforceGovernmentGatewayUser {

        selfAssessmentService.getSelfAssessmentUserType(pertaxContext.authContext) flatMap {

          case NotYetActivatedOnlineFilerSelfAssessmentUser(_) =>
            Future.successful(Redirect(configDecorator.ssoToActivateSaEnrolmentPinUrl))
          case _ =>
            cspPartialService.webchatClickToChatScriptPartial("pertax") map { p =>
              Ok(views.html.selfAssessmentNotShown(p.successfulContentOrEmpty))
            }
        }

      }
  }

  def ivExemptLandingPage(continueUrl: Option[StrictContinueUrl]): Action[AnyContent] = AuthorisedAction() {
    implicit pertaxContext =>

      val c = configDecorator.lostCredentialsChooseAccountUrl(continueUrl.map(_.url).getOrElse(controllers.routes.ApplicationController.index().url))

      val retryUrl = controllers.routes.ApplicationController.uplift(continueUrl).url

      selfAssessmentService.getSelfAssessmentUserType(pertaxContext.authContext) flatMap {
        case ActivatedOnlineFilerSelfAssessmentUser(x) =>
          handleIvExemptAuditing("Activated online SA filer")
          Future.successful(Ok(views.html.activatedSaFilerIntermediate(x.toString, DateTimeTools.previousAndCurrentTaxYear)))
        case NotYetActivatedOnlineFilerSelfAssessmentUser(_) =>
          handleIvExemptAuditing("Not yet activated SA filer")
          Future.successful(Ok(views.html.iv.failure.failedIvContinueToActivateSa()))
        case AmbiguousFilerSelfAssessmentUser(_) =>
          handleIvExemptAuditing("Ambiguous SA filer")
          cspPartialService.webchatClickToChatScriptPartial("pertax") map { p =>
            Ok(views.html.iv.failure.failedIvSaFilerWithNoEnrolment(c, p.successfulContentOrEmpty))
          }

        case NonFilerSelfAssessmentUser =>
          Future.successful(Ok(views.html.iv.failure.cantConfirmIdentity(retryUrl)))
      }
  }

  private def handleIvExemptAuditing(saUserType: String)(implicit hc: HeaderCarrier, pertaxContext: PertaxContext) = {
    auditConnector.sendEvent(buildEvent("saIdentityVerificationBypass", "sa17_exceptions_or_insufficient_evidence", Map("saUserType" -> Some(saUserType))))
  }

}
