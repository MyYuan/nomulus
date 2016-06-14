// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.ui.server.api;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.net.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static google.registry.model.eppcommon.ProtocolDefinition.ServiceExtension.FEE_0_6;
import static google.registry.model.registry.Registries.findTldForNameOrThrow;
import static google.registry.ui.server.SoyTemplateUtils.createTofuSupplier;
import static google.registry.util.DomainNameUtils.canonicalizeDomainName;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.json.simple.JSONValue.toJSONString;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import com.google.common.net.MediaType;
import com.google.template.soy.tofu.SoyTofu;

import dagger.Module;
import dagger.Provides;

import google.registry.config.RegistryEnvironment;
import google.registry.flows.EppException;
import google.registry.flows.EppXmlTransformer;
import google.registry.flows.FlowRunner;
import google.registry.flows.FlowRunner.CommitMode;
import google.registry.flows.FlowRunner.UserPrivileges;
import google.registry.flows.SessionMetadata.SessionSource;
import google.registry.flows.StatelessRequestSessionMetadata;
import google.registry.flows.domain.DomainCheckFlow;
import google.registry.model.domain.fee.FeeCheckResponseExtension;
import google.registry.model.domain.fee.FeeCheckResponseExtension.FeeCheck;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppoutput.CheckData.DomainCheck;
import google.registry.model.eppoutput.CheckData.DomainCheckData;
import google.registry.model.eppoutput.EppResponse;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import google.registry.request.Response;
import google.registry.ui.soy.api.DomainCheckFeeEppSoyInfo;
import google.registry.util.Clock;
import google.registry.util.FormattingLogger;

import java.util.Map;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

/**
 * A servlet that returns availability and premium checks as json.
 *
 * <p>This action returns plain JSON without a safety prefix, so it's vital that the output not be
 * user controlled, lest it open an XSS vector. Do not modify this to return the domain name in the
 * response.
 */
@Action(path = "/check")
public class CheckApiAction implements Runnable {

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  private static final Supplier<SoyTofu> TOFU_SUPPLIER =
      createTofuSupplier(DomainCheckFeeEppSoyInfo.getInstance());

  private final StatelessRequestSessionMetadata sessionMetadata =
      new StatelessRequestSessionMetadata(
          RegistryEnvironment.get().config().getCheckApiServletRegistrarClientId(),
          false,
          false,
          ImmutableSet.of(FEE_0_6.getUri()),
          SessionSource.HTTP);

  @Inject @Parameter("domain") String domain;
  @Inject Response response;
  @Inject Clock clock;
  @Inject CheckApiAction() {}

  @Override
  public void run() {
    Map<String, ?> checkResponse = doCheck(domain);
    response.setHeader("Content-Disposition", "attachment");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
    response.setContentType(MediaType.JSON_UTF_8);
    response.setPayload(toJSONString(checkResponse));
  }

  // TODO(rgr): add whitebox instrumentation for this?
  private Map<String, ?> doCheck(String domainString) {
    try {
      domainString = canonicalizeDomainName(nullToEmpty(domainString));
      // Validate the TLD.
      findTldForNameOrThrow(InternetDomainName.from(domainString));
    } catch (IllegalStateException | IllegalArgumentException e) {
      return fail("Must supply a valid domain name on an authoritative TLD");
    }
    try {
      byte[] inputXmlBytes = TOFU_SUPPLIER.get()
          .newRenderer(DomainCheckFeeEppSoyInfo.DOMAINCHECKFEE)
          .setData(ImmutableMap.of("domainName", domainString))
          .render()
          .getBytes(UTF_8);
      EppResponse response = new FlowRunner(
          DomainCheckFlow.class,
          EppXmlTransformer.<EppInput>unmarshal(inputXmlBytes),
          Trid.create(getClass().getSimpleName()),
          sessionMetadata,
          inputXmlBytes,
          null,
          clock)
              .run(CommitMode.LIVE, UserPrivileges.NORMAL)
              .getResponse();
      DomainCheckData checkData = (DomainCheckData) response.getResponseData().get(0);
      DomainCheck check = (DomainCheck) checkData.getChecks().get(0);
      boolean available = check.getName().getAvail();
      ImmutableMap.Builder<String, Object> builder = new ImmutableMap.Builder<String, Object>()
          .put("status", "success")
          .put("available", available);
      if (available) {
        FeeCheckResponseExtension feeCheckResponse =
            (FeeCheckResponseExtension) response.getExtensions().get(0);
        FeeCheck feeCheck = feeCheckResponse.getChecks().get(0);
        builder.put("tier", firstNonNull(feeCheck.getFeeClass(), "standard"));
      } else {
        builder.put("reason", check.getReason());
      }
      return builder.build();
    } catch (EppException e) {
      return fail(e.getMessage());
    } catch (Exception e) {
      logger.warning(e, "Unknown error");
      return fail("Invalid request");
    }
  }

  private Map<String, String> fail(String reason) {
    return ImmutableMap.of(
        "status", "error",
        "reason", reason);
  }

  /** Dagger module for the check api endpoint. */
  @Module
  public static final class CheckApiModule {
    @Provides
    @Parameter("domain")
    static String provideDomain(HttpServletRequest req) {
      return RequestParameters.extractRequiredParameter(req, "domain");
    }
  }
}