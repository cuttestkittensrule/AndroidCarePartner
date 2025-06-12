package org.tidepool.carepartner

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.IntrinsicSize.Min
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.material3.TopAppBarDefaults.topAppBarColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tidepool.carepartner.FollowActivity.Companion.executor
import org.tidepool.carepartner.FollowUI.InvitationState.*
import org.tidepool.carepartner.backend.DataUpdater
import org.tidepool.carepartner.backend.DataUpdater.FatalDataException
import org.tidepool.carepartner.backend.DataUpdater.TokenExpiredException
import org.tidepool.carepartner.backend.PersistentData
import org.tidepool.carepartner.backend.PersistentData.Companion.logout
import org.tidepool.carepartner.backend.PillData
import org.tidepool.carepartner.backend.WarningType
import org.tidepool.carepartner.backend.WarningType.*
import org.tidepool.carepartner.backend.data.DataRepository
import org.tidepool.carepartner.backend.data.DataState
import org.tidepool.carepartner.backend.data.FakeBackendDataSource
import org.tidepool.carepartner.ui.theme.Grey0300
import org.tidepool.carepartner.ui.theme.LoopFollowTheme
import org.tidepool.carepartner.ui.theme.LoopTheme
import org.tidepool.sdk.model.BloodGlucose.Trend
import org.tidepool.sdk.model.BloodGlucose.Trend.*
import org.tidepool.sdk.model.BloodGlucose.Units
import org.tidepool.sdk.model.confirmations.Confirmation
import org.tidepool.sdk.model.data.DosingDecisionData
import org.tidepool.sdk.model.metadata.Profile
import org.tidepool.sdk.model.mgdl
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle.SHORT
import java.util.Locale
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FollowUI : DefaultLifecycleObserver {
    
    internal var future: ScheduledFuture<*>? = null
    private var updater: DataUpdater? = null
    
    private fun startDataCollection() {
        updater?.let {
            future?.cancel(true)
            future = executor.scheduleWithFixedDelay(
                updater, 0, 1, TimeUnit.MINUTES
            )
        }
    }
    
    private fun stopDataCollection() {
        future?.cancel(false)
        future = null
    }
    
    override fun onResume(owner: LifecycleOwner) {
        startDataCollection()
    }
    
    override fun onPause(owner: LifecycleOwner) {
        stopDataCollection()
    }
    
    @Composable
    fun Invitations(
        invitationsState: State<Array<Confirmation>>,
        userNumber: MutableIntState,
        modifier: Modifier = Modifier,
        isExpanded: MutableState<Boolean> = mutableStateOf(false)
    ) {
        val invitations by invitationsState
        var expanded by isExpanded
        Column(
            modifier = modifier
                .background(MaterialTheme.colorScheme.background)
                .clickable(!expanded) {
                    expanded = true
                }
                .padding(bottom = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HorizontalDivider()
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    invitations.size.let { size ->
                        if (size == 0) {
                            stringResource(R.string.no_invitations)
                        } else {
                            stringResource(R.string.invitations, size)
                        }
                    },
                    color = MaterialTheme.colorScheme.onBackground
                )
                this@Column.AnimatedVisibility(
                    visible = expanded,
                    label = "exit",
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        "Exit Invitations",
                        modifier = Modifier.clickable {
                            expanded = false
                        }
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                
                Spacer(Modifier.weight(1f))
                
            }
            
            
            val currentState = remember(expanded, invitations.isEmpty()) {
                InvitationState.fromExpanded(
                    expanded,
                    invitations.isNotEmpty()
                )
            }
            
            AnimatedContent(
                targetState = currentState,
                label = "Invitations Content"
            ) { state ->
                if (state != Hidden) {
                    HorizontalDivider()
                }
                when (state) {
                    Invitations   -> InvitationsList(invitationsState)
                    NoInvitations -> NoInvitations(userNumber)
                    else          -> Unit
                }
            }
        }
    }
    
    enum class InvitationState {
        Hidden,
        Invitations,
        NoInvitations;
        
        companion object {
            
            fun fromExpanded(expanded: Boolean, hasInvitations: Boolean): InvitationState {
                return if (expanded) {
                    if (hasInvitations) {
                        Invitations
                    } else {
                        NoInvitations
                    }
                } else {
                    Hidden
                }
            }
        }
    }
    
    @Composable
    fun NoInvitations(
        userNumber: MutableIntState
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (userNumber.intValue < 1) {
                Text(
                    stringResource(R.string.no_invitations_message),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Button(
                onClick = {
                    runBlocking {
                        withTimeout(5.seconds) {
                            updater?.updateInvitations()
                        }
                    }
                },
                colors = LoopTheme.current.buttonColors
            ) {
                Text(stringResource(R.string.check_invites))
            }
        }
    }
    
    @Composable
    fun InvitationsList(
        invitationsState: State<Array<Confirmation>>
    ) {
        val invitations by invitationsState
        LazyColumn(
            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 5.dp)
        ) {
            items(
                invitations
            ) {
                Invitation(it)
            }
        }
    }
    
    @Composable
    fun Invitation(confirmation: Confirmation) {
        Card {
            Row(
                modifier = Modifier.padding(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier.weight(1f / 3)
                ) {
                    Text(
                        buildAnnotatedString {
                            val str = stringResource(R.string.invite_message)
                            val split = str.split("%s")
                            append(split[0])
                            
                            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                            append(confirmation.creator?.profile?.fullName ?: "User")
                            pop()
                            
                            append(split[1])
                        },
                        maxLines = 3
                    )
                }
                Column {
                    Row {
                        Image(
                            painterResource(R.drawable.cancel),
                            "Reject",
                            modifier = Modifier
                                .clickable {
                                    runBlocking {
                                        withTimeout(15.seconds) {
                                            updater?.rejectConfirmation(confirmation)
                                        }
                                    }
                                }
                                .padding(horizontal = 5.dp)
                        )
                        Image(
                            painterResource(R.drawable.accept),
                            "Reject",
                            modifier = Modifier
                                .clickable {
                                    runBlocking {
                                        withTimeout(15.seconds) {
                                            updater?.acceptConfirmation(confirmation)
                                        }
                                    }
                                }
                                .padding(horizontal = 5.dp)
                        )
                    }
                }
            }
            
        }
    }
    
    @Preview(name = "Light Mode", showBackground = true, group = "mockup")
    @Preview(
        name = "Dark Mode",
        showBackground = false,
        group = "mockup",
        uiMode = Configuration.UI_MODE_NIGHT_YES
    )
    @Composable
    fun EmptyInvitations() {
        LoopFollowTheme {
            Invitations(
                remember { mutableStateOf(arrayOf()) },
                remember { mutableIntStateOf(0) }
            )
        }
    }
    
    @Preview(name = "Light Mode", showBackground = true, group = "mockup")
    @Preview(
        name = "Dark Mode",
        showBackground = false,
        group = "mockup",
        uiMode = Configuration.UI_MODE_NIGHT_YES
    )
    @Composable
    fun NotEmptyInvitations() {
        LoopFollowTheme {
            Invitations(
                remember {
                    mutableStateOf(
                        arrayOf(
                            Confirmation(
                                creator = Confirmation.Creator(
                                    profile = Profile(
                                        fullName = "Sally Seastar"
                                    )
                                )
                            )
                        )
                    )
                },
                remember { mutableIntStateOf(1) },
            )
        }
    }
    
    
    /**
     * Card that displays the information for a single user
     * @param pillData The data to display
     * @param mutableExpanded The state the determines if the card is expanded
     * @param inMenu if the menu is open
     * @param modifier The [Modifier] to apply
     */
    @Composable
    fun FollowPill(
        pillData: PillData,
        mutableExpanded: MutableState<Boolean>,
        inMenu: Boolean,
        modifier: Modifier = Modifier
    ) {
        var expanded by mutableExpanded
        
        val outerCardColor =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        Card(
            onClick = { expanded = !expanded },
            enabled = !inMenu,
            modifier = modifier,
            colors = outerCardColor
        ) {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 10.dp, start = 5.dp)
                ) {
                    UserImage(pillData)
                    Text(
                        text = pillData.name.split(" ")[0],
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        lineHeight = 24.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    val angle: Float by animateFloatAsState(
                        if (expanded) -180f else 0f,
                        label = "Card Arrow"
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "expand arrow",
                        modifier = Modifier.rotate(angle)
                    )
                }
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    pillData.lastUpdate.rememberKey(null) {
                        val formatter = if (it.until(Instant.now()) >= 1.days) {
                            DateTimeFormatter.ofLocalizedDateTime(SHORT)
                        } else {
                            DateTimeFormatter.ofLocalizedTime(SHORT)
                        }.withZone(ZoneId.systemDefault())
                        formatter.format(it)
                    }?.let {
                        Image(
                            painterResource(R.drawable.sync),
                            "sync",
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                            modifier = Modifier.padding(end = 5.dp)
                        )
                        Text(
                            it,
                            fontSize = 13.sp
                        )
                    }
                }
            }
            
            val innerCardColor = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
            val cardWidth = 140.dp
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Min)
                    .width(Min)
            ) {
                Card(
                    colors = innerCardColor, modifier = Modifier
                        .padding(10.dp)
                        .width(cardWidth)
                        .fillMaxHeight(),
                    shape = ButtonDefaults.shape
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceAround,
                        modifier = Modifier
                            .fillMaxSize()
                    ) {
                        Spacer(Modifier)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                        ) {
                            val color =
                                if (pillData.warningType == Critical) {
                                    LoopTheme.current.critical
                                } else if (pillData.bg?.let { it > 400.mgdl } == true) {
                                    LoopTheme.current.critical
                                } else {
                                    MaterialTheme.colorScheme.onBackground
                                }
                            Text(
                                text = pillData.bg?.let {
                                    when {
                                        it < 40.mgdl  -> "LOW"
                                        it > 400.mgdl -> "HIGH"
                                        else          -> it.toString(PersistentData.unit)
                                    }
                                } ?: "---",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 30.sp,
                                lineHeight = 35.8.sp,
                                color = color,
                                modifier = Modifier.padding(top = 5.dp)
                            )
                            Text(
                                text = PersistentData.unit.shorthand,
                                fontWeight = FontWeight.Normal,
                                fontSize = 11.sp,
                                color = Grey0300
                            )
                        }
                        TrendArrow(pillData.trend, pillData.warningType)
                    }
                }
                Image(
                    painter = painterResource(id = R.drawable.loop_status_icon),
                    contentDescription = "loop status",
                    colorFilter = ColorFilter.tint(LoopTheme.current.loopStatus)
                )
                Card(
                    colors = innerCardColor, modifier = Modifier
                        .padding(10.dp)
                        .width(cardWidth)
                        .fillMaxHeight(),
                    shape = ButtonDefaults.shape
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .padding(5.dp)
                            .fillMaxSize()
                    ) {
                        Text(
                            text = pillData.basalRate?.let {
                                String.format(Locale.getDefault(), "%1.2f", it)
                            } ?: "---",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 30.sp,
                            lineHeight = 35.8.sp,
                            color = LoopTheme.current.insulin
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "U/hr",
                            fontWeight = FontWeight.Normal,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = Grey0300
                        )
                    }
                }
            }
            AnimatedContent(expanded, label = "Card",
                transitionSpec = {
                    val anim =
                        (expandVertically { it } + fadeIn()).togetherWith(shrinkVertically { it } + fadeOut())
                    anim.using(SizeTransform(clip = false))
                }) { cardExpanded ->
                
                if (cardExpanded) {
                    Card(
                        colors = innerCardColor, modifier = Modifier
                            .padding(10.dp)
                            .fillMaxWidth()
                    ) {
                        DetailedInfo(
                            title = "Change in Glucose",
                            name = R.string.reading,
                            lastInstance = pillData.lastGlucose,
                            modifier = Modifier.padding(5.dp)
                        ) {
                            Row {
                                Text(
                                    text = pillData.glucoseChange?.toSignString(PersistentData.unit)
                                        ?: "---",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 24.sp,
                                    lineHeight = 28.64.sp,
                                    color = LoopTheme.current.bloodGlucose,
                                    modifier = Modifier.padding(end = 15.dp)
                                )
                            }
                        }
                        HorizontalDivider()
                        DetailedInfo(
                            title = "Active Insulin",
                            name = R.string.bolus,
                            lastInstance = pillData.lastBolus,
                            modifier = Modifier.padding(5.dp)
                        ) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                val text = pillData.activeInsulin?.amount?.let {
                                    String.format(Locale.getDefault(), "%1.2f", it)
                                } ?: "---"
                                Text(
                                    text = text,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 24.sp,
                                    lineHeight = 28.64.sp,
                                    color = LoopTheme.current.insulin
                                )
                                Text(
                                    text = "U",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp,
                                    lineHeight = 19.09.sp,
                                    color = LoopTheme.current.insulin,
                                    modifier = Modifier.padding(start = 2.dp, end = 15.dp)
                                )
                            }
                        }
                        HorizontalDivider()
                        DetailedInfo(
                            title = "Active Carbs",
                            name = R.string.entry,
                            lastInstance = pillData.lastCarbEntry,
                            modifier = Modifier.padding(5.dp)
                        ) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = pillData.activeCarbs?.amount?.roundToInt()?.toString()
                                        ?: "---",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 24.sp,
                                    lineHeight = 28.64.sp,
                                    color = LoopTheme.current.carbohydrates,
                                )
                                Text(
                                    text = "g",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp,
                                    lineHeight = 19.09.sp,
                                    color = LoopTheme.current.carbohydrates,
                                    modifier = Modifier.padding(start = 2.dp, end = 15.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    
    @Composable
    fun TrendArrow(trend: Trend?, warningType: WarningType) {
        if (trend != null) {
            val id = remember(trend) {
                when (trend) {
                    rapidRise, rapidFall -> R.drawable.double_arrow_up
                    else                 -> R.drawable.flat_arrow
                }
            }
            val rotation = remember(trend) {
                when (trend) {
                    constant     -> 0
                    slowFall     -> 45
                    slowRise     -> -45
                    moderateFall -> 90
                    moderateRise -> -90
                    rapidFall    -> 180
                    rapidRise    -> 0
                }
            }
            val color = when (warningType) {
                Warning  -> LoopTheme.current.warning
                Critical -> LoopTheme.current.critical
                None     -> LoopTheme.current.bloodGlucose
            }
            
            Image(
                painterResource(id),
                "trend: $trend",
                modifier = Modifier
                    .rotate(rotation.toFloat())
                    .padding(start = 5.dp, end = 10.dp),
                colorFilter = ColorFilter.tint(color),
            )
        } else {
            Spacer(Modifier)
        }
    }
    
    
    /**
     * The detailed information that is displayed when the follow pill is expanded.
     * @param title The large text that describes the information in [display]
     * @param name The text to put after "Last" to describe the time ago
     * @param lastInstance The last instance of this thing occurring. If it is null, it will not display a time ago.
     * @param modifier The modifier for this detailedInfo
     * @param display The display for the text that shows the amount.
     */
    @Composable
    fun DetailedInfo(
        title: String,
        @StringRes name: Int,
        lastInstance: Instant?,
        modifier: Modifier = Modifier,
        display: @Composable () -> Unit
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.fillMaxWidth()
        ) {
            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.Normal,
                    fontSize = 17.sp,
                    lineHeight = 20.29.sp,
                    modifier = Modifier.padding(start = 15.dp, top = 15.dp)
                )
                var minutesPast by remember(lastInstance) {
                    mutableStateOf(
                        lastInstance?.until(
                            Instant.now()
                        )
                    )
                }
                
                Text(
                    text = minutesPast?.let { diff ->
                        when {
                            (diff > 1.days)  -> diff.inWholeDays to R.plurals.days
                            (diff > 1.hours) -> diff.inWholeHours to R.plurals.hours
                            else             -> diff.inWholeMinutes to R.plurals.minutes
                        }.let { (quantity, res) ->
                            stringResource(
                                R.string.last_text,
                                stringResource(name),
                                quantity,
                                pluralStringResource(
                                    id = res,
                                    count = quantity.toInt()
                                )
                            )
                        }
                    } ?: stringResource(R.string.empty_last_text, stringResource(name)),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    lineHeight = 16.71.sp,
                    color = Color(0xFF92949C),
                    modifier = Modifier.padding(start = 15.dp, top = 8.dp, bottom = 15.dp)
                )
                LaunchedEffect(key1 = minutesPast) {
                    lastInstance?.let { last ->
                        val timeSince = last.until(Instant.now())
                        val toDelay = if (timeSince >= 1.days) {
                            (timeSince.inWholeDays + 1).days - timeSince
                        } else if (timeSince >= 1.hours) {
                            (timeSince.inWholeHours + 1).hours - timeSince
                        } else {
                            (timeSince.inWholeMinutes + 1).minutes - timeSince
                        }
                        delay(toDelay)
                        minutesPast = last.until(Instant.now())
                    }
                }
            }
            display()
        }
    }
    
    /**
     * The User icon of the current user
     * @param pillData The data to use for displaying the user icon
     */
    @Composable
    fun UserImage(pillData: PillData, modifier: Modifier = Modifier) {
        Icon(
            Icons.Filled.AccountCircle,
            contentDescription = "${pillData.name} image",
            modifier = modifier
                .padding(horizontal = 5.dp)
        )
    }
    
    /**
     * The application to render. This is not in the callback so that if there is a method to
     * simulate communication with the backend, the entire app can be displayed with dummy data.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun App(
        modifier: Modifier = Modifier,
        allData: Array<out PillData>? = null,
        backPressed: MutableState<Boolean> = mutableStateOf(false),
        dataState: DataState = viewModel(
            factory = DataState.Factory,
            extras = MutableCreationExtras().apply {
                if (allData == null) {
                    set(
                        DataState.DATA_REPOSITORY_KEY,
                        DataRepository(
                            FakeBackendDataSource()
                        )
                    )
                } else {
                    val map = allData.associateBy { it.name }
                    set(
                        DataState.DATA_REPOSITORY_KEY,
                        DataRepository(
                            FakeBackendDataSource(foloweeData = map)
                        )
                    )
                }
            }
        )
    ) {
        val idsState = dataState.data.collectAsState()
        val ids by idsState
        val invitations = dataState.invitations.collectAsState()
        val lastError = remember { mutableStateOf<Exception?>(null) }
        var menuVisible by remember { mutableStateOf(false) }
        val invitationsVisible = remember(ids.isEmpty()) { mutableStateOf(ids.isEmpty()) }
        val context = LocalContext.current
        
        if (backPressed.value) {
            backPressed.value = false
            if (menuVisible) {
                menuVisible = false
            } else if (invitationsVisible.value) {
                invitationsVisible.value = false
            }
        }
        
        Box(modifier.fillMaxSize()) {
            lastError.value?.let {
                AlertDialog(
                    title = {
                        Text(stringResource(R.string.error_occurred))
                    },
                    text = {
                        var message: String? = null
                        if (it is FatalDataException) {
                            it.cause?.let { cause ->
                                message =
                                    "${it.message ?: "A Fatal Exception Occurred"}: ${cause::class.qualifiedName ?: "Unknown"}: ${cause.message ?: "No message"}"
                            }
                        }
                        message = message
                            ?: "${it::class.qualifiedName ?: "Unknown"}: ${it.message ?: "No message"}"
                        Text(message!!)
                    },
                    onDismissRequest = {
                        context.handleError(lastError)
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                context.handleError(lastError)
                            }
                        ) {
                            Text(
                                stringResource(R.string.error_acknowledge)
                            )
                        }
                    }
                )
            }
            Scaffold(
                modifier = modifier,
                topBar = {
                    CenterAlignedTopAppBar(
                        colors = topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.primary,
                        ),
                        actions = {
                            Icon(
                                Icons.Filled.AccountCircle,
                                contentDescription = "account",
                                modifier = Modifier
                                    .clickable { menuVisible = true }
                                    .padding(horizontal = 5.dp)
                            )
                        },
                        title = {
                            Text(stringResource(R.string.following))
                        }
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                ) {
                    val states = remember { HashMap<String, MutableState<Boolean>>() }
                    var closedInitial by remember { mutableStateOf(false) }
                    
                    LazyColumn(
                        horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp)
                    ) {
                        items(
                            items = ids.toList(),
                            key = { message ->
                                message.hashCode()
                            }) { (id, data) ->
                            if (ids.size > 1 && !closedInitial) {
                                states.computeIfAbsent(id) { mutableStateOf(false) }.value = false
                                closedInitial = true
                            }
                            val state = states.computeIfAbsent(id) { mutableStateOf(ids.size < 2) }
                            FollowPill(data, state, menuVisible)
                        }
                    }
                    
                    Invitations(
                        invitations,
                        remember(ids) { mutableIntStateOf(ids.size) },
                        Modifier.align(Alignment.BottomCenter),
                        invitationsVisible
                    )
                }
            }
            
            AnimatedVisibility(
                visible = menuVisible,
                enter = slideInVertically {
                    it
                },
                exit = slideOutVertically {
                    it
                }
            ) {
                Menu {
                    menuVisible = false
                }
            }
        }
    }
    
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Menu(modifier: Modifier = Modifier, close: () -> Unit) {
        val context = LocalContext.current
        var unit: Units by remember { mutableStateOf(PersistentData.unit) }
        Scaffold(
            topBar = {
                Column {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                text = stringResource(R.string.my_account), fontSize = 24.sp,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier
                                    .padding(vertical = 15.dp)
                            )
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    PersistentData.unit = unit
                                    close()
                                }
                            ) {
                                Text(stringResource(R.string.done))
                            }
                        }
                    )
                    HorizontalDivider()
                }
            },
            modifier = modifier
                .background(MaterialTheme.colorScheme.background)
                .fillMaxSize(),
        ) { internalPadding ->
            Column(modifier = Modifier.padding(internalPadding)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        PersistentData.lastName ?: "User",
                        fontWeight = FontWeight.W600,
                        fontSize = 28.sp,
                        modifier = Modifier.padding(20.dp)
                    )
                    Icon(
                        Icons.Filled.AccountCircle,
                        "User Icon",
                        modifier = Modifier
                            .padding(20.dp)
                            .size(DpSize(33.dp, 33.dp))
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.Top,
                    modifier = modifier.fillMaxWidth()
                ) {
                    Text(
                        PersistentData.lastEmail ?: "wavedashing@madeline.celeste.com",
                        fontWeight = FontWeight.W400,
                        fontSize = 17.sp,
                        modifier = Modifier.padding(start = 20.dp, top = 0.dp, bottom = 15.dp)
                    )
                }
                HorizontalDivider()
                Text(
                    stringResource(R.string.unit_select),
                    modifier = Modifier
                        .padding(top = 15.dp, bottom = 5.dp)
                        .padding(start = 20.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(start = 20.dp)
                        .clickable {
                            unit = Units.milligramsPerDeciliter
                        }
                ) {
                    RadioButton(
                        unit == Units.milligramsPerDeciliter,
                        {
                            unit = Units.milligramsPerDeciliter
                        }
                    )
                    Text(Units.milligramsPerDeciliter.shorthand)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(start = 20.dp)
                        .clickable {
                            unit = Units.millimolesPerLiter
                        }
                ) {
                    RadioButton(
                        unit == Units.millimolesPerLiter,
                        {
                            unit = Units.millimolesPerLiter
                        }
                    )
                    Text(Units.millimolesPerLiter.shorthand)
                }
                
                Spacer(Modifier.weight(1f))
                HorizontalDivider()
                var dialogOpen by remember { mutableStateOf(false) }
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            dialogOpen = true
                        },
                        colors = LoopTheme.current.buttonColors,
                        modifier = Modifier
                            .padding(horizontal = 15.dp, vertical = 10.dp)
                            .fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.logout_button_text)
                        )
                    }
                }
                if (dialogOpen) {
                    AlertDialog(
                        title = {
                            Text(stringResource(R.string.logout_prompt))
                        },
                        text = {
                            Text(stringResource(R.string.logout_message))
                        },
                        onDismissRequest = {
                            dialogOpen = false
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    future?.cancel(true)
                                    context.logout()
                                }
                            ) {
                                Text(
                                    stringResource(R.string.logout_confirm),
                                    color = LoopTheme.current.critical
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    dialogOpen = false
                                }
                            ) {
                                Text(stringResource(R.string.logout_cancel))
                            }
                        }
                    )
                }
            }
        }
    }
    
    @Preview(name = "Light Mode", showBackground = true, group = "mockup")
    @Preview(
        name = "Dark Mode",
        showBackground = false,
        group = "mockup",
        uiMode = Configuration.UI_MODE_NIGHT_YES
    )
    @Composable
    fun MenuPreview() {
        LoopFollowTheme {
            Menu {
            
            }
        }
    }
    
    @Preview
    @Composable
    fun WholeAppTest() {
        LoopFollowTheme {
            App(
                allData = arrayOf(
                    PillData(
                        150.mgdl,
                        5.mgdl,
                        "Test",
                        1.0,
                        DosingDecisionData.CarbsOnBoard(
                            null, 10.0
                        ),
                        DosingDecisionData.InsulinOnBoard(
                            null, 2.0
                        ),
                        Instant.now() - 1.minutes,
                        Instant.now() - 2.minutes,
                        Instant.now() - 1.5.days,
                        constant
                    )
                )
            )
        }
    }
    
    @Preview(name = "Light Mode", showBackground = false, group = "component")
    @Preview(
        name = "Dark Mode",
        showBackground = false,
        group = "dark mode",
        uiMode = Configuration.UI_MODE_NIGHT_YES
    )
    @Composable
    fun FollowerPreview() {
        LoopFollowTheme {
            FollowPill(
                PillData(130.mgdl),
                mutableExpanded = remember { mutableStateOf(false) },
                inMenu = false,
            )
        }
    }
    
    @Preview(name = "Light Mode", showBackground = false, group = "component")
    @Preview(
        name = "Dark Mode",
        showBackground = false,
        group = "dark mode",
        uiMode = Configuration.UI_MODE_NIGHT_YES
    )
    @Composable
    fun ExpandedFollowerPreview() {
        LoopFollowTheme {
            val lastReading = Instant.now() - 1.minutes
            val lastBolus = Instant.now() - 2.minutes
            val lastEntry = Instant.now() - 2.hours
            // val lastUpdate = arrayOf(lastReading, lastBolus, lastEntry).max()
            val lastUpdate = Instant.now() - 1.5.days
            FollowPill(
                PillData(
                    130.mgdl,
                    lastGlucose = lastReading,
                    lastBolus = lastBolus,
                    lastCarbEntry = lastEntry,
                    glucoseChange = (-5).mgdl,
                    trend = rapidRise,
                    warningType = Warning,
                    lastUpdate = lastUpdate
                ),
                mutableExpanded = remember { mutableStateOf(true) },
                inMenu = false
            )
        }
    }
    
    private fun Context.handleError(state: MutableState<Exception?>) {
        var value by state
        if (value is FatalDataException) {
            future?.cancel(true)
            when (value) {
                is TokenExpiredException -> retryAuthorize()
                else                     -> logout()
            }
        }
        value = null
    }
}

@Composable
inline fun <T, R> T.rememberKey(crossinline calculation: @DisallowComposableCalls (T) -> R): R {
    return remember(this) { calculation(this) }
}

@Composable
inline fun <T, R> T?.rememberKey(
    default: R,
    crossinline calculation: @DisallowComposableCalls (T) -> R
): R {
    return remember(this) { this?.let { calculation(it) } ?: default }
}

fun Context.retryAuthorize() {
    if (numRetries++ < MAX_RETRIES) {
        authorize()
    } else {
        logout()
    }
}

const val MAX_RETRIES: Int = 4
var numRetries: Int = 0