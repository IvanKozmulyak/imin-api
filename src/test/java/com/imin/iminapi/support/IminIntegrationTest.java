package com.imin.iminapi.support;

import com.imin.iminapi.audienceplan.config.SummaryChatClient;
import com.imin.iminapi.audienceplan.service.PortraitLlmClient;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.email.ResendDomainsClient;
import com.imin.iminapi.marketing.graph.MetaGraphClient;
import com.imin.iminapi.marketing.sms.BirdSmsClient;
import com.imin.iminapi.oauth.AppleNativeIdentityService;
import com.imin.iminapi.oauth.AppleOAuthService;
import com.imin.iminapi.oauth.GoogleOAuthService;
import com.imin.iminapi.predictor.research.ResearchLlmClient;
import com.imin.iminapi.push.ExpoPushSender;
import com.imin.iminapi.service.poster.IdeogramV3Client;
import com.imin.iminapi.service.poster.RecraftClient;
import com.stripe.StripeClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The one shared integration context: real beans on one Postgres container, external boundaries faked here.
 * Test classes add nothing that changes the context; {@link SpringContextGuardTest} enforces it.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
@MockitoBean(types = {StripeClient.class, CampaignEmailProvider.class, ResendDomainsClient.class,
        BirdSmsClient.class, ExpoPushSender.class, MetaGraphClient.class, IdeogramV3Client.class,
        RecraftClient.class, ResearchLlmClient.class, PortraitLlmClient.class, ChatClient.class})
@MockitoBean(name = SummaryChatClient.NAME, types = ChatClient.class)
@MockitoSpyBean(types = {GoogleOAuthService.class, AppleOAuthService.class, AppleNativeIdentityService.class})
@TestExecutionListeners(listeners = IminIntegrationResetListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface IminIntegrationTest {
}
