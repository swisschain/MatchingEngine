package com.lykke.matching.engine.services

import com.lykke.matching.engine.AbstractTest
import com.lykke.matching.engine.config.TestApplicationContext
import com.lykke.matching.engine.daos.setting.AvailableSettingGroups
import com.lykke.matching.engine.database.TestSettingsDatabaseAccessor
import com.lykke.matching.engine.utils.getSetting
import com.lykke.matching.engine.web.dto.SettingDto
import junit.framework.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.junit4.SpringRunner
import kotlin.test.assertNotNull

@RunWith(SpringRunner::class)
@SpringBootTest(classes = [(TestApplicationContext::class), (BalanceUpdateServiceTest.Config::class)])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ApplicationSettingsServiceTest: AbstractTest() {

    @Autowired
    private lateinit var applicationSettingsService: ApplicationSettingsService

    @Autowired
    private lateinit var testSettingsDatabaseAccessor: TestSettingsDatabaseAccessor

    @Test
    fun getAllSettingGroupsTest() {
        //given
        testSettingsDatabaseAccessor.clear()
        testSettingsDatabaseAccessor.createOrUpdateSetting(AvailableSettingGroups.TRUSTED_CLIENTS.settingGroupName, getSetting("testClient"))
        testSettingsDatabaseAccessor.createOrUpdateSetting(AvailableSettingGroups.DISABLED_ASSETS.settingGroupName, getSetting("BTC"))

        //when
        val allSettingGroups = applicationSettingsService.getAllSettingGroups()

        //then
        assertEquals(2, allSettingGroups.size)

        val trustedClients = allSettingGroups.find { it.groupName == AvailableSettingGroups.TRUSTED_CLIENTS.settingGroupName }
        assertNotNull(trustedClients)

        val disabledAssets = allSettingGroups.find { it.groupName == AvailableSettingGroups.DISABLED_ASSETS.settingGroupName }
        assertNotNull(disabledAssets)

        assertEquals("testClient", trustedClients!!.settings.first().value)
        assertEquals("BTC", disabledAssets!!.settings.first().value)
    }

    @Test
    fun getSettingsGroupTest() {
        //given
        testSettingsDatabaseAccessor.clear()
        testSettingsDatabaseAccessor.createOrUpdateSetting(AvailableSettingGroups.TRUSTED_CLIENTS.settingGroupName, getSetting("testClient"))

        //when
        val settingsGroup = applicationSettingsService.getSettingsGroup(AvailableSettingGroups.TRUSTED_CLIENTS)

        //then
        assertNotNull(settingsGroup)
        assertEquals(1, settingsGroup!!.settings.size)
        assertEquals("testClient", settingsGroup.settings.first().value)
    }

    @Test
    fun getSettingTest() {
        //given
        testSettingsDatabaseAccessor.clear()
        testSettingsDatabaseAccessor.createOrUpdateSetting(AvailableSettingGroups.TRUSTED_CLIENTS.settingGroupName, getSetting("testClient", "settingName"))

        //when
        val setting = applicationSettingsService.getSetting(AvailableSettingGroups.TRUSTED_CLIENTS, "settingName")

        //then
        assertNotNull(setting)
        assertEquals("testClient", setting!!.value)
    }

    @Test
    fun createOrUpdateSettingTest() {
        //given
        testSettingsDatabaseAccessor.clear()
        testSettingsDatabaseAccessor.createOrUpdateSetting(AvailableSettingGroups.TRUSTED_CLIENTS.settingGroupName, getSetting("testClient", "settingName"))

        //when
        applicationSettingsService.createOrUpdateSetting(AvailableSettingGroups.TRUSTED_CLIENTS, SettingDto("settingName", "test", true, "testComment"))

        //then
        val dbSetting = testSettingsDatabaseAccessor.getSetting(AvailableSettingGroups.TRUSTED_CLIENTS.settingGroupName, "settingName")
        assertNotNull(dbSetting)
        assertEquals("test", dbSetting!!.value)
        assertEquals("testComment", dbSetting.comment)

        assertTrue(applicationSettingsCache.isTrustedClient("test"))
    }

    @Test
    fun deleteSettingsGroupTest() {
        //given
        testSettingsDatabaseAccessor.clear()
        testSettingsDatabaseAccessor.createOrUpdateSetting(AvailableSettingGroups.TRUSTED_CLIENTS.settingGroupName, getSetting("testClient", "settingName"))

        //when
        applicationSettingsService.deleteSettingsGroup(AvailableSettingGroups.TRUSTED_CLIENTS)

        //then
        val dbSetting = testSettingsDatabaseAccessor.getSetting(AvailableSettingGroups.TRUSTED_CLIENTS.settingGroupName, "settingName")
        assertNull(dbSetting)

        assertFalse(applicationSettingsCache.isTrustedClient("testClient"))
    }

    @Test
    fun deleteSettingTest() {
        //given
        testSettingsDatabaseAccessor.clear()
        testSettingsDatabaseAccessor.createOrUpdateSetting(AvailableSettingGroups.TRUSTED_CLIENTS.settingGroupName, getSetting("testClient", "settingName"))

        //when
        applicationSettingsService.deleteSetting(AvailableSettingGroups.TRUSTED_CLIENTS, "settingName")

        //then
        val dbSetting = testSettingsDatabaseAccessor.getSetting(AvailableSettingGroups.TRUSTED_CLIENTS.settingGroupName, "settingName")
        assertNull(dbSetting)

        assertFalse(applicationSettingsCache.isTrustedClient("testClient"))
    }
}