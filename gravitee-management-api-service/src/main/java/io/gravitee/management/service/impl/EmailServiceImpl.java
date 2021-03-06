/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.management.service.impl;

import freemarker.template.Configuration;
import freemarker.template.Template;
import io.gravitee.management.service.EmailNotification;
import io.gravitee.management.service.EmailService;
import io.gravitee.management.service.exceptions.TechnicalManagementException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils;

import javax.activation.MimetypesFileTypeMap;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author Azize ELAMRANI (azize.elamrani at graviteesource.com)
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class EmailServiceImpl extends TransactionalService implements EmailService {

    private final Logger LOGGER = LoggerFactory.getLogger(EmailServiceImpl.class);

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private Configuration freemarkerConfiguration;

    @Value("${templates.path:${gravitee.home}/templates}")
    private String templatesPath;

    @Value("${email.subject:[Gravitee.io] %s}")
    private String subject;

    @Value("${email.enabled:false}")
    private boolean enabled;

    @Value("${email.from}")
    private String defaultFrom;

    public void sendEmailNotification(final EmailNotification emailNotification) {
        if (enabled) {
            try {
                MimeMessageHelper mailMessage = new MimeMessageHelper(mailSender.createMimeMessage(), true, StandardCharsets.UTF_8.name());
                final Template template = freemarkerConfiguration.getTemplate(emailNotification.getContent());
                final String htmlText =
                        FreeMarkerTemplateUtils.processTemplateIntoString(template, emailNotification.getParams());

                final String from = Objects.isNull(emailNotification.getFrom()) || emailNotification.getFrom().isEmpty()
                        ? defaultFrom
                        : emailNotification.getFrom();

                mailMessage.setFrom(from);
                mailMessage.setTo(emailNotification.getTo());
                mailMessage.setSubject(String.format(subject, emailNotification.getSubject()));

                final String html = addResourcesInMessage(mailMessage, htmlText);

                LOGGER.debug("Sending an email to: {}\nSubject: {}\nMessage: {}",
                        emailNotification.getTo(), emailNotification.getSubject(), html);

                mailSender.send(mailMessage.getMimeMessage());
            } catch (final Exception ex) {
                LOGGER.error("Error while sending email notification", ex);
                throw new TechnicalManagementException("Error while sending email notification", ex);
            }
        }
    }

    @Async
    public void sendAsyncEmailNotification(final EmailNotification emailNotification) {
        sendEmailNotification(emailNotification);
    }

    private String addResourcesInMessage(final MimeMessageHelper mailMessage, final String htmlText) throws Exception {
        final Document document = Jsoup.parse(htmlText);

        final List<String> resources = new ArrayList<>();

        final Elements imageElements = document.getElementsByTag("img");
        resources.addAll(imageElements.stream()
                .filter(imageElement -> imageElement.hasAttr("src"))
                .map(imageElement -> {
                    final String src = imageElement.attr("src");
                    imageElement.attr("src", "cid:" + src);
                    return src;
                })
                .collect(Collectors.toList()));

        final String html = document.html();
        mailMessage.setText(html, true);

        for (final String res : resources) {
            final FileSystemResource templateResource = new FileSystemResource(new File(templatesPath, res));
            mailMessage.addInline(res, templateResource, MimetypesFileTypeMap.getDefaultFileTypeMap().getContentType(res));
        }

        return html;
    }
}
