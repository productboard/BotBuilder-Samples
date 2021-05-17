// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.bot.sample.teamssearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.bot.builder.TurnContext;
import com.microsoft.bot.builder.teams.TeamsActivityHandler;
import com.microsoft.bot.schema.ActionTypes;
import com.microsoft.bot.schema.CardAction;
import com.microsoft.bot.schema.CardImage;
import com.microsoft.bot.schema.HeroCard;
import com.microsoft.bot.schema.Serialization;
import com.microsoft.bot.schema.ThumbnailCard;
import com.microsoft.bot.schema.teams.MessageActionsPayloadUser;
import com.microsoft.bot.schema.teams.MessagingExtensionAction;
import com.microsoft.bot.schema.teams.MessagingExtensionActionResponse;
import com.microsoft.bot.schema.teams.MessagingExtensionAttachment;
import com.microsoft.bot.schema.teams.MessagingExtensionParameter;
import com.microsoft.bot.schema.teams.MessagingExtensionQuery;
import com.microsoft.bot.schema.teams.MessagingExtensionResponse;
import com.microsoft.bot.schema.teams.MessagingExtensionResult;
import com.microsoft.bot.schema.teams.MessagingExtensionSuggestedAction;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class implements the functionality of the Bot.
 *
 * <p>This is where application specific logic for interacting with the users would be
 * added.  This sample illustrates how to build a Search-based Messaging Extension.</p>
 */

public class TeamsMessagingExtensionsSearchBot extends TeamsActivityHandler {
    private final Map<String, String> userIdToName = new ConcurrentHashMap<>();

    @Override
    protected CompletableFuture<MessagingExtensionActionResponse> onTeamsMessagingExtensionSubmitAction(
            TurnContext turnContext,
            MessagingExtensionAction action
    ) {
        String userId = getUserId(action);
        maybeProcessLogIn(turnContext, userId);

        String userName = getUserName(userId);
        // userName not stored, but maybe just logged-in
        if (userName == null) {
            return requestAuth();
        } else {
            return loggedInMessage(userName);
        }
    }

    private String getUserName(String userId) {
        return userId != null ? userIdToName.get(userId) : null;
    }

    private void maybeProcessLogIn(TurnContext turnContext, String userId) {
        if (userId == null) return;
        String state = ((Map<String, String>) turnContext.getActivity().getValue()).get("state");
        if (state != null) {
            userIdToName.put(userId, state);
        }
    }

    private String getUserId(MessagingExtensionAction action) {
        MessageActionsPayloadUser user = action.getMessagePayload().getFrom().getUser();
        return user != null ? user.getId() : null;
    }

    private CompletableFuture<MessagingExtensionActionResponse> loggedInMessage(String userName) {
        ThumbnailCard card = new ThumbnailCard();
        card.setTitle("Insight added by " + userName);

        MessagingExtensionAttachment attachment = new MessagingExtensionAttachment();
        attachment.setContentType(ThumbnailCard.CONTENTTYPE);
        attachment.setContent(card);

        MessagingExtensionResult composeExtension = new MessagingExtensionResult();
        composeExtension.setType("result");
        composeExtension.setAttachmentLayout("list");
        composeExtension.setAttachments(Collections.singletonList(attachment));
        MessagingExtensionActionResponse response = new MessagingExtensionActionResponse();
        response.setComposeExtension(composeExtension);
        return CompletableFuture.completedFuture(response);
    }

    private CompletableFuture<MessagingExtensionActionResponse> requestAuth() {
        CardAction cardAction = new CardAction();
        cardAction.setType(ActionTypes.OPEN_URL);
        cardAction.setValue("https://186e3a27e70b.ngrok.io/login.html");
        cardAction.setTitle("Sign in to this app");

        MessagingExtensionSuggestedAction suggestedAction = new MessagingExtensionSuggestedAction();
        suggestedAction.setAction(cardAction);

        MessagingExtensionResult composeExtension = new MessagingExtensionResult();
        composeExtension.setType("auth");
        composeExtension.setSuggestedActions(suggestedAction);

        MessagingExtensionActionResponse response = new MessagingExtensionActionResponse();
        response.setComposeExtension(composeExtension);
        return CompletableFuture.completedFuture(response);
    }

    @Override
    protected CompletableFuture<MessagingExtensionResponse> onTeamsMessagingExtensionQuery(
            TurnContext turnContext,
            MessagingExtensionQuery query
    ) {
        List<MessagingExtensionParameter> queryParams = query.getParameters();
        String text = "";
        if (queryParams != null && !queryParams.isEmpty()) {
            text = (String) queryParams.get(0).getValue();
        }

        return findPackages(text)
                .thenApply(packages -> {
                    List<MessagingExtensionAttachment> attachments = new ArrayList<>();
                    for (String[] item : packages) {
                        ObjectNode data = Serialization.createObjectNode();
                        data.set("data", Serialization.objectToTree(item));

                        CardAction cardAction = new CardAction();
                        cardAction.setType(ActionTypes.INVOKE);
                        cardAction.setValue(Serialization.toStringSilent(data));
                        ThumbnailCard previewCard = new ThumbnailCard();
                        previewCard.setTitle(item[0]);
                        previewCard.setTap(cardAction);

                        if (!StringUtils.isEmpty(item[4])) {
                            CardImage cardImage = new CardImage();
                            cardImage.setUrl(item[4]);
                            cardImage.setAlt("Icon");
                            previewCard.setImages(Collections.singletonList(cardImage));
                        }

                        HeroCard heroCard = new HeroCard();
                        heroCard.setTitle(item[0]);

                        MessagingExtensionAttachment attachment = new MessagingExtensionAttachment();
                        attachment.setContentType(HeroCard.CONTENTTYPE);
                        attachment.setContent(heroCard);
                        attachment.setPreview(previewCard.toAttachment());

                        attachments.add(attachment);
                    }

                    MessagingExtensionResult composeExtension = new MessagingExtensionResult();
                    composeExtension.setType("result");
                    composeExtension.setAttachmentLayout("list");
                    composeExtension.setAttachments(attachments);

                    return new MessagingExtensionResponse(composeExtension);
                });
    }

    @Override
    protected CompletableFuture<MessagingExtensionResponse> onTeamsMessagingExtensionSelectItem(
            TurnContext turnContext,
            Object query
    ) {

        Map cardValue = (Map) query;
        List<String> data = (ArrayList) cardValue.get("data");
        CardAction cardAction = new CardAction();
        cardAction.setType(ActionTypes.OPEN_URL);
        cardAction.setTitle("Project");
        cardAction.setValue(data.get(3));

        ThumbnailCard card = new ThumbnailCard();
        card.setTitle(data.get(0));
        card.setSubtitle(data.get(2));
        card.setButtons(Arrays.asList(cardAction));

        if (!StringUtils.isEmpty(data.get(4))) {
            CardImage cardImage = new CardImage();
            cardImage.setUrl(data.get(4));
            cardImage.setAlt("Icon");
            card.setImages(Collections.singletonList(cardImage));
        }

        MessagingExtensionAttachment attachment = new MessagingExtensionAttachment();
        attachment.setContentType(ThumbnailCard.CONTENTTYPE);
        attachment.setContent(card);

        MessagingExtensionResult composeExtension = new MessagingExtensionResult();
        composeExtension.setType("result");
        composeExtension.setAttachmentLayout("list");
        composeExtension.setAttachments(Collections.singletonList(attachment));
        return CompletableFuture.completedFuture(new MessagingExtensionResponse(composeExtension));
    }

    private CompletableFuture<List<String[]>> findPackages(String text) {
        return CompletableFuture.supplyAsync(() -> {
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                    .url(String
                            .format(
                                    "https://azuresearch-usnc.nuget.org/query?q=id:%s&prerelease=true",
                                    text
                            ))
                    .build();

            List<String[]> filteredItems = new ArrayList<>();
            try {
                Response response = client.newCall(request).execute();
                JsonNode obj = Serialization.jsonToTree(response.body().string());
                ArrayNode dataArray = (ArrayNode) obj.get("data");

                for (int i = 0; i < dataArray.size(); i++) {
                    JsonNode item = dataArray.get(i);
                    filteredItems.add(new String[]{
                            item.get("id").asText(),
                            item.get("version").asText(),
                            item.get("description").asText(),
                            item.has("projectUrl") ? item.get("projectUrl").asText() : "",
                            item.has("iconUrl") ? item.get("iconUrl").asText() : ""
                    });
                }
            } catch (IOException e) {
                LoggerFactory.getLogger(TeamsMessagingExtensionsSearchBot.class)
                        .error("findPackages", e);
                throw new CompletionException(e);
            }
            return filteredItems;
        });
    }
}
