/*
 * Copyright 2017 HM Revenue & Customs
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

package models

import controllers.AddressControllerConfiguration
import org.joda.time.DateTime
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.{eq => meq, _}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import play.api.Application
import util.BaseSpec
import play.api.inject.bind
import uk.gov.hmrc.time.TaxYearResolver

class TaxCalculationStateSpec extends BaseSpec {

  override implicit lazy val app: Application = localGuiceApplicationBuilder
    .overrides(bind[AddressControllerConfiguration].toInstance(MockitoSugar.mock[AddressControllerConfiguration]))
    .build()

  trait TaxCalculationStateSimpleSpecSetup {
    lazy val controller = injected[TaxCalculationStateFactory]
  }

  trait TaxCalculationStateCurrentDateSpecSetup {
    def currentDateTime: String

    lazy val controller = {
      when(injected[AddressControllerConfiguration].maxStartDate) thenReturn DateTime.parse(currentDateTime).withTimeAtStartOfDay
      injected[TaxCalculationStateFactory]
    }
  }

  "Calling buildFromTaxCalcSummary without P302 business reasons" should {

    "return a TaxCalcRefundState when called with a TaxCalculation with a P800 status of Overpaid and paymentStatus of REFUND" in new TaxCalculationStateSimpleSpecSetup {
      val taxCalculation = TaxCalculation("Overpaid", 1000.0, 2015, Some("REFUND"), None, None, None)
      val result = controller.buildFromTaxCalculation(Some(taxCalculation))
      result shouldBe TaxCalculationOverpaidRefundState(1000.0, 2015, 2016)
    }

    "return a TaxCalculationPaymentProcessingState when called with a TaxCalculation with a P800 status of Overpaid and paymentStatus of PAYMENT_PROCESSING" in new TaxCalculationStateSimpleSpecSetup {
      val taxCalculation = TaxCalculation("Overpaid", 1000.0, 2015, Some("PAYMENT_PROCESSING"), None, None, None)
      val result = controller.buildFromTaxCalculation(Some(taxCalculation))
      result shouldBe TaxCalculationOverpaidPaymentProcessingState(1000.0)
    }

    "return a TaxCalculationPaymentPaidState when called with a TaxCalculation with a P800 status of Overpaid and paymentStatus of PAYMENT_PAID" in new TaxCalculationStateSimpleSpecSetup {
      val taxCalculation = TaxCalculation("Overpaid", 1000.0, 2015, Some("PAYMENT_PAID"), Some("19 May 2016"), None, None)
      val result = controller.buildFromTaxCalculation(Some(taxCalculation))
      result shouldBe TaxCalculationOverpaidPaymentPaidState(1000.0, "19 May 2016")
    }

    "return a TaxCalculationPaymentChequeSentState when called with a TaxCalculation with a P800 status of Overpaid and paymentStatus of CHEQUE_SENT" in new TaxCalculationStateSimpleSpecSetup {
      val taxCalculation = TaxCalculation("Overpaid", 1000.0, 2015, Some("CHEQUE_SENT"), Some("19 May 2016"), None, None)
      val result = controller.buildFromTaxCalculation(Some(taxCalculation))
      result shouldBe TaxCalculationOverpaidPaymentChequeSentState(1000.0, "19 May 2016")
    }

    "return a TaxCalculation not found when called without a TaxCalculation" in new TaxCalculationStateSimpleSpecSetup {
      val result = controller.buildFromTaxCalculation(None)
      result shouldBe TaxCalculationUnkownState
    }

    "return a TaxCalculationPaymentDueState when called with a TaxCalculation with a P800 status of PAYMENT_DUE" in new TaxCalculationStateSimpleSpecSetup {
      val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PAYMENT_DUE"), None, None, None)
      val result = controller.buildFromTaxCalculation(Some(taxCalculation))
      result shouldBe TaxCalculationUnderpaidPaymentDueState(1000.0, 2015, 2016, None)
    }

    "return a TaxCalculationPartPaidState when called with a TaxCalculation with a P800 status of PART_PAID" in new TaxCalculationStateSimpleSpecSetup {
      val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PART_PAID"), None, None, None)
      val result = controller.buildFromTaxCalculation(Some(taxCalculation))
      result shouldBe TaxCalculationUnderpaidPartPaidState(1000.0, 2015, 2016, None)
    }

    "return a TaxCalculationPaidAllState when called with a TaxCalculation with a P800 status of PAID_ALL" in new TaxCalculationStateSimpleSpecSetup {
      val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PAID_ALL"), None, None, None)
      val result = controller.buildFromTaxCalculation(Some(taxCalculation))
      result shouldBe TaxCalculationUnderpaidPaidAllState(2015, 2016, None)
    }

    "return a TaxCalculationPaymentsDownState when called with a TaxCalculation with a P800 status of PAYMENTS_DOWN" in new TaxCalculationStateSimpleSpecSetup {
      val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PAYMENTS_DOWN"), None, None, None)
      val result = controller.buildFromTaxCalculation(Some(taxCalculation))
      result shouldBe TaxCalculationUnderpaidPaymentsDownState(2015, 2016)
    }
  }

  "Calling buildFromTaxCalcSummary with P302 business reasons" should {

    //  Scenario 1: Simple Assessment customer,PAYMENT_DUE, Due date 31st January, Deadline approaching [Need "Make Payment" link]
    //  GIVEN Taxcalc have sent you p800_status = Underpaid
    //  AND Business Reason: P302
    //  AND a ‘dueDate’ equal to 31/01/2017
    //    AND payment_status = PAYMENT_DUE
    //  THEN display ‘deadline approaching’ message on 15th December for customers with a due date of 31st January

    "display 'deadline approaching' message on or after 15th December for p800 status of 'PAYMENT_DUE'" should {

      "return a TaxCalculationUnderpaidPaymentDueState with SaDeadlineStatus of None and due date when called with: -\n" +
        "  - p800 status of PAYMENT_DUE,\n" +
        "  - a dueDate equal to 31/01/2018\n" +
        "  - and current date less than 15/12/2017" in new TaxCalculationStateCurrentDateSpecSetup {
        override lazy val currentDateTime = "2017-12-14"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PAYMENT_DUE"), None, Some("P302"), Some("2018-01-31"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPaymentDueState(1000.0, 2015, 2016, Some("2018-01-31"), None)
      }

      "return a TaxCalculationUnderpaidPaymentDueState with SaDeadlineStatus of SaDeadlineApproaching and due date when called with: -\n" +
        "  - p800 status of PAYMENT_DUE,\n" +
        "  - a dueDate equal to 31/01/2018\n" +
        "  - and the current date equal to 15/12/2017" in new TaxCalculationStateCurrentDateSpecSetup {
        override lazy val currentDateTime = "2017-12-15"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PAYMENT_DUE"), None, Some("P302"), Some("2018-01-31"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPaymentDueState(1000.0, 2015, 2016, Some("2018-01-31"), Some(SaDeadlineApproachingStatus))
      }

      "return a TaxCalculationUnderpaidPaymentDueState with SaDeadlineStatus of SaDeadlineApproaching and due date when called with: -\n" +
        "  - p800 status of PAYMENT_DUE,\n" +
        "  - a dueDate equal to 31/01/2018\n" +
        "  - and the current date greater than 15/12/2017" in new TaxCalculationStateCurrentDateSpecSetup {
        override lazy val currentDateTime = "2017-12-16"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PAYMENT_DUE"), None, Some("P302"), Some("2018-01-31"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPaymentDueState(1000.0, 2015, 2016, Some("2018-01-31"), Some(SaDeadlineApproachingStatus))
      }
    }

    //  Scenario 2: Simple Assessment customer, PART_PAID, Due date 31st January, Deadline approaching [Need "Make Payment" link]
    //  GIVEN Taxcalc have sent you p800_status = Underpaid
    //  AND Business Reason: P302
    //  AND a ‘dueDate’ equal to 31/01/2017
    //    AND payment_status =PART_PAID
    //  THEN display the ‘deadline approaching’ message on 15th December for customers with a due date of 31st January

    "display 'deadline approaching' message on or after 15th December for p800 status of 'PART_PAID'" should {

      "return a TaxCalculationUnderpaidPartPaidState with SaDeadlineStatus of None and due date when called with: -\n" +
        "  - p800 status of PAYMENT_DUE,\n" +
        "  - a dueDate equal to 31/01/2018\n" +
        "  - and current date less than 15/12/2017" in new TaxCalculationStateCurrentDateSpecSetup {
        override lazy val currentDateTime = "2017-12-14"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PART_PAID"), None, Some("P302"), Some("2018-01-31"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPartPaidState(1000.0, 2015, 2016, Some("2018-01-31"), None)
      }

      "return a TaxCalculationUnderpaidPartPaidState with SaDeadlineStatus of SaDeadlineApproaching and due date when called with: -\n" +
        "  - p800 status of PART_PAID,\n" +
        "  - a dueDate equal to 31/01/2018\n" +
        "  - and the current date equal to 15/12/2017" in new TaxCalculationStateCurrentDateSpecSetup {
        override lazy val currentDateTime = "2017-12-15"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PART_PAID"), None, Some("P302"), Some("2018-01-31"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPartPaidState(1000.0, 2015, 2016, Some("2018-01-31"), Some(SaDeadlineApproachingStatus))
      }

      "return a TaxCalculationUnderpaidPartPaidState with SaDeadlineStatus of SaDeadlineApproaching and due date when called with: -\n" +
        "  - p800 status of PART_PAID,\n" +
        "  - a dueDate equal to 31/01/2018\n" +
        "  - and the current date greater than 15/12/2017" in new TaxCalculationStateCurrentDateSpecSetup {
        override lazy val currentDateTime = "2017-12-16"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PART_PAID"), None, Some("P302"), Some("2018-01-31"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPartPaidState(1000.0, 2015, 2016, Some("2018-01-31"), Some(SaDeadlineApproachingStatus))
      }
    }

    //  Scenario 3: Simple Assessment customer, PAYMENT_DUE, Due date NOT 31st January, Deadline approaching
    //    GIVEN Taxcalc have sent you p800_status = Underpaid
    //  AND Business Reason: P302
    //  AND a ‘dueDate’ NOT equal to 31/01/2017
    //    AND payment_status = PAYMENT_DUE
    //  THEN display the ‘deadline approaching’ message 30 days before the due date

    "display 'deadline approaching' message 30 days before due date for p800 status of 'PAYMENT_DUE'" should {

      "return a TaxCalculationUnderpaidPaymentDueState with SaDeadlineStatus of None when and due date called with: -\n" +
        "  - p800 status of PAYMENT_DUE,\n" +
        "  - a dueDate NOT equal to 31/01/2018\n" +
        "  - and current date less than dueDate minus 31 days" in new TaxCalculationStateCurrentDateSpecSetup {

        override lazy val currentDateTime = "2018-10-30"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PAYMENT_DUE"), None, Some("P302"), Some("2018-11-30"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPaymentDueState(1000.0, 2015, 2016, Some("2018-11-30"), None)
      }

      "return a TaxCalculationUnderpaidPaymentDueState with SaDeadlineStatus of SaDeadlineApproaching and due date when called with: -\n" +
        "  - p800 status of PAYMENT_DUE,\n" +
        "  - a dueDate NOT equal to 31/01/2018\n" +
        "  - and the current date is equal to dueDate minus 30 days" in new TaxCalculationStateCurrentDateSpecSetup {
        override lazy val currentDateTime = "2018-10-31"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PAYMENT_DUE"), None, Some("P302"), Some("2018-11-30"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPaymentDueState(1000.0, 2015, 2016, Some("2018-11-30"), Some(SaDeadlineApproachingStatus))
      }

      "return a TaxCalculationUnderpaidPaymentDueState with SaDeadlineStatus of SaDeadlineApproaching and due date when called with: -\n" +
        "  - p800 status of PAYMENT_DUE,\n" +
        "  - a dueDate NOT equal to 31/01/2018\n" +
        "  - and the current date greater dueDate minus 29 days" in new TaxCalculationStateCurrentDateSpecSetup {
        override lazy val currentDateTime = "2018-11-01"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PAYMENT_DUE"), None, Some("P302"), Some("2018-11-30"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPaymentDueState(1000.0, 2015, 2016, Some("2018-11-30"), Some(SaDeadlineApproachingStatus))
      }
    }

    //  Scenario 4: Simple Assessment customer, PART_PAID, Due date NOT 31st January,Deadline approaching
    //    GIVEN Taxcalc have sent you p800_status = Underpaid
    //  AND Business Reason: P302
    //  AND a ‘dueDate’ equal NOT to 31/01/2017
    //    AND payment_status = PART_PAID
    //  THEN display the ‘deadline approaching’ message 30 days before the due date

    "display 'deadline approaching' message 30 days before due date for p800 status of 'PART_PAID'" should {

      "return a TaxCalculationUnderpaidPartPaidState with SaDeadlineStatus of None and due date when called with: -\n" +
        "  - p800 status of PART_PAID,\n" +
        "  - a dueDate NOT equal to 31/01/2018\n" +
        "  - and current date less than dueDate minus 31 days" in new TaxCalculationStateCurrentDateSpecSetup {

        override lazy val currentDateTime = "2018-10-30"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PART_PAID"), None, Some("P302"), Some("2018-11-30"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPartPaidState(1000.0, 2015, 2016, Some("2018-11-30"), None)
      }

      "return a TaxCalculationUnderpaidPartPaidState with SaDeadlineStatus of SaDeadlineApproaching and due date when called with: -\n" +
        "  - p800 status of PART_PAID,\n" +
        "  - a dueDate NOT equal to 31/01/2018\n" +
        "  - and the current date is equal to dueDate minus 30 days" in new TaxCalculationStateCurrentDateSpecSetup {
        override lazy val currentDateTime = "2018-10-31"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PART_PAID"), None, Some("P302"), Some("2018-11-30"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPartPaidState(1000.0, 2015, 2016, Some("2018-11-30"), Some(SaDeadlineApproachingStatus))
      }

      "return a TaxCalculationUnderpaidPartPaidState with SaDeadlineStatus of SaDeadlineApproaching and due date when called with: -\n" +
        "  - p800 status of PART_PAID,\n" +
        "  - a dueDate NOT equal to 31/01/2018\n" +
        "  - and the current date greater dueDate minus 29 days" in new TaxCalculationStateCurrentDateSpecSetup {
        override lazy val currentDateTime = "2018-11-01"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PART_PAID"), None, Some("P302"), Some("2018-11-30"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPartPaidState(1000.0, 2015, 2016, Some("2018-11-30"), Some(SaDeadlineApproachingStatus))
      }
    }

    //  Scenario 5: Simple Assessment customer, PAYMENT_DUE, Due date 31st January, Deadline passed
    //    GIVEN Taxcalc have sent you p800_status = Underpaid
    //  AND Business Reason: P302
    //  AND a ‘dueDate’ equal to 31/01/2017
    //    AND payment_status = PAYMENT_DUE
    //  THEN display the ‘deadline passed’ message on 1st February for customers with a due date of 31st January

    "display 'deadline passed' message on or after 1st February for p800 status of 'PAYMENT_DUE'" should {

      "return a TaxCalculationUnderpaidPaymentDueState with SaDeadlineStatus of None and due date when called with: -\n" +
        "  - p800 status of PAYMENT_DUE,\n" +
        "  - a dueDate equal to 31/01/2018\n" +
        "  - and current date less than 01/02/2018" in new TaxCalculationStateCurrentDateSpecSetup {
        override lazy val currentDateTime = "2018-01-31"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PAYMENT_DUE"), None, Some("P302"), Some("2018-01-31"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPaymentDueState(1000.0, 2015, 2016, Some("2018-01-31"), None)
      }

      "return a TaxCalculationUnderpaidPaymentDueState with SaDeadlineStatus of SaDeadlinePassedStatus and due date when called with: -\n" +
        "  - p800 status of PAYMENT_DUE,\n" +
        "  - a dueDate equal to 31/01/2018\n" +
        "  - and the current date equal to 01/02/2018" in new TaxCalculationStateCurrentDateSpecSetup {
        override lazy val currentDateTime = "2018-02-01"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PAYMENT_DUE"), None, Some("P302"), Some("2018-01-31"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPaymentDueState(1000.0, 2015, 2016, Some("2018-01-31"), Some(SaDeadlinePassedStatus))
      }

      "return a TaxCalculationUnderpaidPaymentDueState with SaDeadlineStatus of SaDeadlinePassedStatus and due date when called with: -\n" +
        "  - p800 status of PAYMENT_DUE,\n" +
        "  - a dueDate equal to 31/01/2018\n" +
        "  - and the current date greater than 01/02/2018" in new TaxCalculationStateCurrentDateSpecSetup {
        override lazy val currentDateTime = "2018-02-02"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PAYMENT_DUE"), None, Some("P302"), Some("2018-01-31"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPaymentDueState(1000.0, 2015, 2016, Some("2018-01-31"), Some(SaDeadlinePassedStatus))
      }
    }

    //  Scenario 6: Simple Assessment customer, PART_PAID, Due date 31st January, Deadline passed
    //    GIVEN Taxcalc have sent you p800_status = Underpaid
    //  AND Business Reason: P302
    //  AND a ‘dueDate’ equal to 31/01/2017
    //    AND payment_status = PART_PAID
    //  THEN display the ‘deadline passed’ message on 1st February for customers with a due date of 31st January
    "display 'deadline passed' message on or after 1st February for p800 status of 'PART_PAID'" should {

      "return a TaxCalculationUnderpaidPartPaidState with SaDeadlineStatus of None and due date when called with: -\n" +
        "  - p800 status of PAYMENT_DUE,\n" +
        "  - a dueDate equal to 31/01/2018\n" +
        "  - and current date less than 01/02/2018" in new TaxCalculationStateCurrentDateSpecSetup {
        override lazy val currentDateTime = "2018-01-31"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PART_PAID"), None, Some("P302"), Some("2018-01-31"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPartPaidState(1000.0, 2015, 2016, Some("2018-01-31"), None)
      }

      "return a TaxCalculationUnderpaidPartPaidState with SaDeadlineStatus of SaDeadlinePassedStatus and due date when called with: -\n" +
        "  - p800 status of PART_PAID,\n" +
        "  - a dueDate equal to 31/01/2018\n" +
        "  - and the current date equal to 01/02/2018" in new TaxCalculationStateCurrentDateSpecSetup {
        override lazy val currentDateTime = "2018-02-01"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PART_PAID"), None, Some("P302"), Some("2018-01-31"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPartPaidState(1000.0, 2015, 2016, Some("2018-01-31"), Some(SaDeadlinePassedStatus))
      }

      "return a TaxCalculationUnderpaidPartPaidState with SaDeadlineStatus of SaDeadlinePassedStatus and due date when called with: -\n" +
        "  - p800 status of PART_PAID,\n" +
        "  - a dueDate equal to 31/01/2018\n" +
        "  - and the current date greater than 01/02/2018" in new TaxCalculationStateCurrentDateSpecSetup {
        override lazy val currentDateTime = "2018-02-02"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PART_PAID"), None, Some("P302"), Some("2018-01-31"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPartPaidState(1000.0, 2015, 2016, Some("2018-01-31"), Some(SaDeadlinePassedStatus))
      }
    }
    
    //  Scenario 7: Simple Assessment customer, PAYMENT_DUE, Due date NOT 31st January, Deadline passed
    //    GIVEN Taxcalc have sent you p800_status = Underpaid
    //  AND Business Reason: P302
    //  AND a ‘dueDate’ NOT equal to 31/01/2017
    //    AND payment_status = PAYMENT_DUE
    //  THEN display the ‘deadline passed’ message 1 day after the customers due date
    
    "display 'deadline passed' message 1 day after due date for p800 status of 'PAYMENT_DUE'" should {

      "return a TaxCalculationUnderpaidPaymentDueState with SaDeadlineStatus of SaDeadlinePassedStatus and due date when called with: -\n" +
        "  - p800 status of PAYMENT_DUE,\n" +
        "  - a dueDate NOT equal to 31/01/2018\n" +
        "  - and the current date is greater than dueDate" in new TaxCalculationStateCurrentDateSpecSetup {
        override lazy val currentDateTime = "2018-12-01"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PAYMENT_DUE"), None, Some("P302"), Some("2018-11-30"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPaymentDueState(1000.0, 2015, 2016, Some("2018-11-30"), Some(SaDeadlinePassedStatus))
      }
    }

    //  Scenario 8: Simple Assessment customer, PART_PAID, Due date NOT 31st January,Deadline passed
    //    GIVEN Taxcalc have sent you p800_status = Underpaid
    //  AND Business Reason: P302
    //  AND a ‘dueDate’ NOT equal to 31/01/2017
    //    AND payment_status = PART_PAID
    //  THEN display the ‘deadline passed’ message 1 day after the customers due date

    "return a TaxCalculationUnderpaidPaymentDueState with SaDeadlineStatus of SaDeadlinePassedStatus and due date when called with: -\n" +
      "  - p800 status of PART_PAID,\n" +
      "  - a dueDate NOT equal to 31/01/2018\n" +
      "  - and the current date is greater than dueDate" in new TaxCalculationStateCurrentDateSpecSetup {
      override lazy val currentDateTime = "2018-12-01"
      val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PART_PAID"), None, Some("P302"), Some("2018-11-30"))
      val result = controller.buildFromTaxCalculation(Some(taxCalculation))
      result shouldBe TaxCalculationUnderpaidPartPaidState(1000.0, 2015, 2016, Some("2018-11-30"), Some(SaDeadlinePassedStatus))
    }
  }

  //  Scenario 9: Simple Assessment customer, PAID_ALL, Due date ANY
    //  GIVEN Taxcalc have sent you a ‘dueDate’
    //  AND Business Reason: P302
    //  AND Payment_ Status = PAID_ALL
    //  WHEN the customer views the PTA tile
    //  THEN display ‘You don’t owe any Tax’

    "display 'you don't owe any tax' message p800 status of 'PAID_ALL'" should {

      "return a TaxCalculationUnderpaidPaidAllState with due date: -\n" +
        "  - p800 status of PAID_ALL,\n" +
        "  - any dueDate\n" +
        "  - any current date" in new TaxCalculationStateCurrentDateSpecSetup {
        override lazy val currentDateTime = "2018-01-31"
        val taxCalculation = TaxCalculation("Underpaid", 1000.0, 2015, Some("PAID_ALL"), None, Some("P302"), Some("2018-01-31"))
        val result = controller.buildFromTaxCalculation(Some(taxCalculation))
        result shouldBe TaxCalculationUnderpaidPaidAllState(2015, 2016, Some("2018-01-31"))
      }
    }

    //  Scenario 10: P800, Any
    //  GIVEN Taxcalc have NOT sent you a ‘dueDate’
    //  AND there is no Business Reason
    //    WHEN the customer views the PTA tile
    //  THEN display existing tile
}
