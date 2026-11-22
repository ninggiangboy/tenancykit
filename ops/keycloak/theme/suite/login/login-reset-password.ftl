<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<#--
  The keycloak.v2 reset-password page with one difference. When the request names a tenant (`suiteTenant`, set by the
  tenant reset step), the field asks for an email and the instruction says so: the tenant prefix is added for them, as
  it is on the sign-in page. Without a tenant the page is the stock one.
-->
<@layout.registrationLayout displayInfo=true displayMessage=!messagesPerField.existsError('username'); section>
    <#if section = "header">
        ${msg("emailForgotTitle")}
    <#elseif section = "form">
        <form id="kc-reset-password-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <#assign label>
                <#if suiteTenant??>${msg("email")}<#elseif !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if>
            </#assign>
            <#-- The attempted username is the tenant-prefixed one, which is not what the person typed. -->
            <@field.input name="username" label=label value=(suiteTenant??)?then('', auth.attemptedUsername!'') autofocus=true />

            <@buttons.actionGroup>
              <@buttons.button id="kc-form-buttons" label="doSubmit"/>
              <@buttons.buttonLink href=url.loginUrl label="backToLogin"/>
            </@buttons.actionGroup>

        </form>
    <#elseif section = "info" >
        <span class="${properties.kcLoginMainFooterHelperText!}">
            <#if suiteTenant??>
                ${msg("emailInstructionTenant", suiteTenant)}
            <#elseif realm.duplicateEmailsAllowed>
                ${msg("emailInstructionUsername")}
            <#else>
                ${msg("emailInstruction")}
            </#if>
        </span>
    </#if>
</@layout.registrationLayout>
