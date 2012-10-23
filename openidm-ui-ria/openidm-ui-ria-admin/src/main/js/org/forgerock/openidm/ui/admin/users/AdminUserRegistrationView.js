/**
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011-2012 ForgeRock AS. All rights reserved.
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://forgerock.org/license/CDDLv1.0.html
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://forgerock.org/license/CDDLv1.0.html
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 */

/*global define, $, form2js, _, ContentFlow */

/**
 * @author mbilski
 */
define("org/forgerock/openidm/ui/admin/users/AdminUserRegistrationView", [
    "org/forgerock/commons/ui/common/main/AbstractView",
    "org/forgerock/commons/ui/common/main/ValidatorsManager",
    "org/forgerock/commons/ui/common/util/UIUtils",
    "org/forgerock/commons/ui/user/delegates/UserDelegate",
    "org/forgerock/commons/ui/common/main/EventManager",
    "org/forgerock/commons/ui/common/util/Constants"
], function(AbstractView, validatorsManager, uiUtils, userDelegate, eventManager, constants) {
    var AdminUserRegistrationView = AbstractView.extend({
        template: "templates/admin/AdminUserRegistrationTemplate.html",
        events: {
            "click input[type=submit]": "formSubmit",
            "onValidate": "onValidate"
        },
        
        formSubmit: function(event) {
            event.preventDefault();
            
            if(validatorsManager.formValidated(this.$el) && !this.isFormLocked()) {
                this.lock();
                
                var data = form2js(this.$el.attr("id")), element;
                
                delete data.terms;
                delete data.passwordConfirm;
                data.userName = data.email.toLowerCase();
                data.securityQuestion = 1;
                data.securityAnswer = "";
                
                console.log("ADDING USER: " + JSON.stringify(data));                
                userDelegate.createEntity(data, function(user) {
                    eventManager.sendEvent(constants.EVENT_USER_SUCCESSFULY_REGISTERED, { user: data, selfRegistration: false });
                }, function(response) {
                    console.warn(response);
                    if (response.error === 'Conflict') {
                        eventManager.sendEvent(constants.EVENT_DISPLAY_MESSAGE_REQUEST, "userAlreadyExists" );
                    } else {
                        eventManager.sendEvent(constants.EVENT_DISPLAY_MESSAGE_REQUEST, "unknown" );
                    }
                    this.unlock();
                });
            }
        },
        
        render: function() {
            this.parentRender(function() {
                validatorsManager.bindValidators(this.$el);
                this.unlock();
            });            
        }   
    }); 
    
    return new AdminUserRegistrationView();
});

