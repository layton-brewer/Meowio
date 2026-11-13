import re,sys
def resolve(path, choices):
    s=open(path).read()
    pat=re.compile(r'<<<<<<< desktop\n(.*?)=======\n(.*?)>>>>>>> meowio\n', re.S)
    i=[0]
    def rep(m):
        d,mw=m.group(1),m.group(2)
        c=choices[i[0]]; i[0]+=1
        if c=='desktop': return d
        if c=='meowio': return mw
        if c=='both': return d+mw
        if c=='both_mw_first': return mw+d
        return c(d,mw)
    out=pat.sub(rep,s)
    assert i[0]==len(choices),(path,i[0],len(choices))
    open(path,'w').write(out)
base='/tmp/w/dm/commonMain/kotlin/com/nuvio/app/'
R=resolve
R(base+'AppShellComponents.kt',['both'])
R(base+'PlayerDestination.kt',['desktop'])
R(base+'features/updater/AppUpdaterRepository.kt',['desktop','desktop'])
tail="                }\n            }\n        }\n    }\n"
R(base+'features/settings/AdvancedSettingsPage.kt',[lambda d,m: m+tail+d])
R(base+'features/home/components/HomeHeroSection.kt',['desktop'])
R(base+'features/player/PlayerScreenRuntimeGestureActions.kt',['desktop'])
R(base+'features/player/PlayerScreenRuntimeUi.kt',['desktop'])
R(base+'core/ui/Components.kt',[lambda d,m: "            .background(effectiveContainerColor)\n            .then(\n                if (effectiveContainerColor.alpha > 0f) {\n                    Modifier.border(NuvioGlass.RimWidth, NuvioGlass.Rim, shape)\n                } else {\n                    Modifier\n                },\n            )\n"])
R(base+'core/sync/ProfileSettingsSync.kt',[lambda d,m:"private const val PUSH_DEBOUNCE_MS = 500L\nprivate const val BACKUP_APP = \"meowio-mobile\"\nconst val BACKUP_FILE_NAME = \"meowio-mobile-backup.json\"\n"])
R(base+'features/details/MetaDetailsScreen.kt',['desktop','desktop','desktop'])
R(base+'MainTabsDestination.kt',['desktop','both',lambda d,m: d.split('\n')[0]+'\n'+m])
