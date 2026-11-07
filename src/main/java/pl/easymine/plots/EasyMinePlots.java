package pl.easymine.plots;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import java.io.*;
import java.util.*;

public final class EasyMinePlots extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
  private static final String PRE="§8[§aEasyMinePlots§8] §7";
  private static final String MAIN="§2§lEasyMinePlots", MEMBERS="§2§lCzłonkowie działki", SETTINGS="§2§lUstawienia działki";
  private final Map<UUID,Plot> plots=new HashMap<>();
  private final Map<UUID,UUID> invites=new HashMap<>();
  private final Map<UUID,Long> inviteExpiry=new HashMap<>();
  private final Map<UUID,BukkitTask> borders=new HashMap<>();
  private final Map<UUID,UUID> previous=new HashMap<>();
  private final Map<UUID,UUID> shownNight=new HashMap<>();
  private File file;
  private Economy eco;
  private NamespacedKey itemKey,displayKey;
  private static final String[] FLAGS={"pvp","potwory","zwierzeta","wejscie","skrzynie","drzwi","eksplozje","ogien","latanie","noc"};
  private static final String[] LABELS={"PvP","Potwory","Zwierzęta","Wejście obcych","Skrzynie obcych","Drzwi obcych","Eksplozje","Rozprzestrzenianie ognia","Latanie","Ciągła noc"};
  private static final Material[] ICONS={Material.IRON_SWORD,Material.ZOMBIE_HEAD,Material.EGG,Material.OAK_DOOR,Material.CHEST,Material.IRON_DOOR,Material.TNT,Material.FLINT_AND_STEEL,Material.FEATHER,Material.CLOCK};
  private static final class Plot {
    UUID owner; String world; int x,y,z,level=1; Set<UUID> members=new HashSet<>(), deputies=new HashSet<>(); Map<String,Boolean> flags=new HashMap<>();
    Plot(UUID o,String w,int x,int y,int z){owner=o;world=w;this.x=x;this.y=y;this.z=z;}
    // Blok działki jest punktem odniesienia. Przy parzystym rozmiarze: -8..+7.
    int minX(){return x-level*8;}
    int minZ(){return z-level*8;}
    int maxX(){return minX()+level*16-1;}
    int maxZ(){return minZ()+level*16-1;}
    boolean contains(Location l){return l.getWorld()!=null&&l.getWorld().getName().equals(world)&&l.getBlockX()>=minX()&&l.getBlockX()<=maxX()&&l.getBlockZ()>=minZ()&&l.getBlockZ()<=maxZ();}
    boolean can(UUID id){return owner.equals(id)||members.contains(id)||deputies.contains(id);}
    boolean manager(UUID id){return owner.equals(id)||deputies.contains(id);}
    boolean flag(String s){return flags.getOrDefault(s,false);}
  }
  @Override public void onEnable(){
    saveDefaultConfig(); itemKey=new NamespacedKey(this,"blok_dzialki"); displayKey=new NamespacedKey(this,"model_dzialki"); file=new File(getDataFolder(),"dzialki.yml"); load();
    if(getServer().getPluginManager().getPlugin("Vault")!=null){RegisteredServiceProvider<Economy> r=getServer().getServicesManager().getRegistration(Economy.class);if(r!=null)eco=r.getProvider();}
    Objects.requireNonNull(getCommand("dzialka")).setExecutor(this);
    Objects.requireNonNull(getCommand("dzialka")).setTabCompleter(this);
    Bukkit.getScheduler().runTaskLater(this,this::refreshDisplays,20L);
    getServer().getPluginManager().registerEvents(this,this);
    getLogger().info("EasyMinePlots uruchomiony. Ekonomia: "+(eco==null?"BRAK Vault/Economy":"aktywna"));
  }
  @Override public void onDisable(){borders.values().forEach(BukkitTask::cancel);for(UUID id:shownNight.keySet()){Player p=Bukkit.getPlayer(id);if(p!=null)p.resetPlayerTime();}save();}
  private void tell(CommandSender p,String s){p.sendMessage(PRE+s);}
  private String name(UUID id){String n=Bukkit.getOfflinePlayer(id).getName();return n==null?id.toString().substring(0,8):n;}
  private Plot at(Location l){for(Plot a:plots.values())if(a.contains(l))return a;return null;}
  private Plot own(UUID id){return plots.get(id);}
  private boolean overlaps(Plot a,int level){
    int mnX=a.minX(),mnZ=a.minZ(),mxX=a.maxX(),mxZ=a.maxZ();
    for(Plot b:plots.values())if(b!=a&&b.world.equals(a.world)&&mnX<=b.maxX()&&mxX>=b.minX()&&mnZ<=b.maxZ()&&mxZ>=b.minZ())return true;
    return false;
  }
  private static final String[] SUBCOMMANDS={"blok","ulepsz","granice","home","ustawienia","czlonkowie","zapros","akceptuj","zastepca","degraduj","wyrzuc","info"};
  @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
    if(!(sender instanceof Player p))return Collections.emptyList();
    Plot a=own(p.getUniqueId());List<String> choices=new ArrayList<>();
    if(args.length==1){for(String sub:SUBCOMMANDS){if(sub.equals("blok")&&a!=null)continue;if((sub.equals("ulepsz")||sub.equals("granice")||sub.equals("home")||sub.equals("ustawienia")||sub.equals("czlonkowie"))&&a==null)continue;if((sub.equals("zapros")||sub.equals("wyrzuc"))&&(a==null||!a.manager(p.getUniqueId())))continue;if((sub.equals("zastepca")||sub.equals("degraduj"))&&(a==null||!a.owner.equals(p.getUniqueId())))continue;choices.add(sub);}}
    else if(args.length==2){String sub=args[0].toLowerCase(Locale.ROOT);if(sub.equals("zapros")&&a!=null&&a.manager(p.getUniqueId())){for(Player other:Bukkit.getOnlinePlayers())if(!other.equals(p))choices.add(other.getName());}else if(a!=null){Set<UUID> ids=switch(sub){case "wyrzuc","zastepca" -> a.members;case "degraduj" -> a.deputies;default -> Collections.emptySet();};for(UUID id:ids)choices.add(name(id));}}
    String prefix=args[args.length-1].toLowerCase(Locale.ROOT);return choices.stream().filter(s->s.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
  }
  private boolean isAnchor(Block b,Plot a){return b.getWorld().getName().equals(a.world)&&b.getX()==a.x&&b.getY()==a.y&&b.getZ()==a.z;}
  private void spawnDisplay(Plot a){World w=Bukkit.getWorld(a.world);if(w==null)return;Location l=new Location(w,a.x+0.5,a.y+0.5,a.z+0.5);if(!l.isChunkLoaded())return;
    for(org.bukkit.entity.Entity ent:w.getNearbyEntities(l,1,1,1))if(ent instanceof ItemDisplay d&&d.getPersistentDataContainer().has(displayKey,PersistentDataType.STRING))d.remove();
    if(w.getBlockAt(a.x,a.y,a.z).getType()!=Material.LODESTONE)return;
    w.spawn(l,ItemDisplay.class,d->{d.setItemStack(plotBlock());d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);d.setPersistent(true);d.setDisplayWidth(0);d.setDisplayHeight(0);d.getPersistentDataContainer().set(displayKey,PersistentDataType.STRING,a.owner.toString());});
  }
  private void refreshDisplays(){for(Plot a:plots.values())spawnDisplay(a);}
  @EventHandler public void chunkLoad(org.bukkit.event.world.ChunkLoadEvent e){for(Plot a:plots.values())if(a.world.equals(e.getWorld().getName())&&Math.floorDiv(a.x,16)==e.getChunk().getX()&&Math.floorDiv(a.z,16)==e.getChunk().getZ())Bukkit.getScheduler().runTaskLater(this,()->spawnDisplay(a),1L);}
  private boolean admin(Player p){return p.hasPermission("easymineplots.admin");}
  private ItemStack icon(Material m,String n,String... lore){ItemStack i=new ItemStack(m);ItemMeta meta=i.getItemMeta();meta.setDisplayName(n);meta.setLore(Arrays.asList(lore));i.setItemMeta(meta);return i;}
  private ItemStack plotBlock(){ItemStack i=icon(Material.LODESTONE,"§a§lBlok Działki","§7Postaw, aby utworzyć działkę 16×16.","§7Zarządzanie: /dzialka");ItemMeta m=i.getItemMeta();m.getPersistentDataContainer().set(itemKey,PersistentDataType.BYTE,(byte)1);m.setItemModel(new NamespacedKey("easymine","blok_dzialki"));i.setItemMeta(m);return i;}
  private boolean isBlock(ItemStack i){return i!=null&&i.hasItemMeta()&&i.getItemMeta().getPersistentDataContainer().has(itemKey,PersistentDataType.BYTE);}
  private void menu(Player p){Inventory v=Bukkit.createInventory(null,27,MAIN);v.setItem(10,icon(Material.LODESTONE,"§aBlok Działki","§7Odbierz: /dzialka blok"));v.setItem(12,icon(Material.EMERALD,"§aUlepsz działkę","§7Kliknij, aby kupić następny poziom"));v.setItem(14,icon(Material.PLAYER_HEAD,"§eCzłonkowie","§7Zarządzaj członkami i zastępcami"));v.setItem(16,icon(Material.COMPARATOR,"§bUstawienia","§7Zmień zasady na działce"));v.setItem(22,icon(Material.LIME_STAINED_GLASS_PANE,"§aPokaż granice","§7Zielone cząsteczki przez 30 sekund"));p.openInventory(v);}
  private void membersMenu(Player p){Inventory v=Bukkit.createInventory(null,27,MEMBERS);v.setItem(10,icon(Material.PLAYER_HEAD,"§aDodaj członka","§7Komenda: /dzialka zapros <nick>"));v.setItem(12,icon(Material.GOLDEN_HELMET,"§eNadaj zastępcę","§7Komenda: /dzialka zastepca <nick>"));v.setItem(14,icon(Material.BARRIER,"§cWyrzuć członka","§7Komenda: /dzialka wyrzuc <nick>"));v.setItem(16,icon(Material.BOOK,"§bLista członków","§7Komenda: /dzialka czlonkowie"));v.setItem(22,icon(Material.ARROW,"§7Powrót"));p.openInventory(v);}
  private void settingsMenu(Player p){Plot a=own(p.getUniqueId());if(a==null){tell(p,"Nie masz działki.");return;}if(!a.owner.equals(p.getUniqueId())){tell(p,"Ustawienia może zmieniać tylko właściciel.");return;}Inventory v=Bukkit.createInventory(null,27,SETTINGS);for(int k=0;k<FLAGS.length;k++)v.setItem(k,icon(ICONS[k],"§e"+LABELS[k],"§7Stan: "+(a.flag(FLAGS[k])?"§aWŁ.":"§cWYŁ."),"§7Kliknij, aby zmienić"));v.setItem(22,icon(Material.ARROW,"§7Powrót"));p.openInventory(v);}
  private void upgrade(Player p){Plot a=own(p.getUniqueId());if(a==null){tell(p,"Najpierw postaw Blok Działki.");return;}int max=Math.min(8,getConfig().getInt("maksymalny-poziom",8));if(a.level>=max){tell(p,"Masz maksymalny poziom działki.");return;}int next=a.level+1;List<Integer> prices=getConfig().getIntegerList("ceny-ulepszen");double cost=next-1<prices.size()?prices.get(next-1):next*5000;
    if(eco==null){tell(p,"Brak ekonomii Vault + EssentialsX.");return;}if(!eco.has(p,cost)){tell(p,"Potrzebujesz §e"+cost+" $§7.");return;}
    a.level++;if(overlaps(a,a.level)){a.level--;tell(p,"Nie możesz ulepszyć: nowy teren nachodzi na inną działkę.");return;}var response=eco.withdrawPlayer(p,cost);if(!response.transactionSuccess()){a.level--;tell(p,"Nie udało się pobrać pieniędzy.");return;}save();tell(p,"Ulepszono działkę do §a"+(a.level*16)+"×"+(a.level*16)+"§7 za §e"+cost+" $§7.");
  }
  private void border(Player p){Plot a=own(p.getUniqueId());if(a==null){tell(p,"Nie masz działki.");return;}BukkitTask old=borders.remove(p.getUniqueId());if(old!=null)old.cancel();World w=Bukkit.getWorld(a.world);if(w==null)return;final int[] count={0};BukkitTask task=Bukkit.getScheduler().runTaskTimer(this,()->{if(!p.isOnline()||count[0]++>=30){BukkitTask t=borders.remove(p.getUniqueId());if(t!=null)t.cancel();return;}int y=p.getLocation().getBlockY()+1;for(int x=a.minX();x<=a.maxX()+1;x+=2){p.spawnParticle(Particle.DUST,new Location(w,x,y,a.minZ()),1,new Particle.DustOptions(Color.LIME,1.4f));p.spawnParticle(Particle.DUST,new Location(w,x,y,a.maxZ()+1),1,new Particle.DustOptions(Color.LIME,1.4f));}for(int z=a.minZ();z<=a.maxZ()+1;z+=2){p.spawnParticle(Particle.DUST,new Location(w,a.minX(),y,z),1,new Particle.DustOptions(Color.LIME,1.4f));p.spawnParticle(Particle.DUST,new Location(w,a.maxX()+1,y,z),1,new Particle.DustOptions(Color.LIME,1.4f));}},0L,20L);borders.put(p.getUniqueId(),task);tell(p,"Wyświetlam zielone granice przez 30 sekund.");}
  @Override public boolean onCommand(CommandSender sender,Command cmd,String alias,String[] args){if(!(sender instanceof Player p)){tell(sender,"Ta komenda wymaga gracza.");return true;}if(args.length==0){menu(p);return true;}String sub=args[0].toLowerCase(Locale.ROOT);Plot a=own(p.getUniqueId());switch(sub){
    case "blok" -> {if(a!=null){tell(p,"Masz już działkę.");break;}p.getInventory().addItem(plotBlock()).forEach((slot,item)->p.getWorld().dropItemNaturally(p.getLocation(),item));tell(p,"Otrzymano Blok Działki. Postaw go w wybranym miejscu.");}
    case "ulepsz" -> upgrade(p);
    case "granice" -> border(p);
    case "ustawienia" -> settingsMenu(p);
    case "czlonkowie" -> {if(a==null){tell(p,"Nie masz działki.");break;}tell(p,"Właściciel: §e"+name(a.owner));tell(p,"Zastępcy: §e"+names(a.deputies));tell(p,"Członkowie: §e"+names(a.members));}
    case "home" -> {if(a==null){tell(p,"Nie masz działki.");break;}World w=Bukkit.getWorld(a.world);if(w!=null)p.teleport(new Location(w,a.x+0.5,a.y+1,a.z+0.5));}
    case "zapros" -> {if(a==null||!a.manager(p.getUniqueId())){tell(p,"Nie możesz zapraszać.");break;}Player target=args.length>1?Bukkit.getPlayerExact(args[1]):null;if(target==null||target.equals(p)){tell(p,"Użycie: /dzialka zapros <gracz online>");break;}invites.put(target.getUniqueId(),a.owner);inviteExpiry.put(target.getUniqueId(),System.currentTimeMillis()+60000);tell(target,"§e"+name(a.owner)+" §7zaprasza Cię na działkę. Wpisz §a/dzialka akceptuj §7w ciągu 60 sekund.");tell(p,"Wysłano zaproszenie.");}
    case "akceptuj" -> {UUID owner=invites.remove(p.getUniqueId());Long expires=inviteExpiry.remove(p.getUniqueId());Plot target=owner==null?null:own(owner);if(target==null||expires==null||System.currentTimeMillis()>expires){tell(p,"Nie masz ważnego zaproszenia.");break;}target.members.add(p.getUniqueId());save();tell(p,"Dołączono do działki gracza "+name(owner)+".");}
    case "zastepca" -> {if(a==null||!a.owner.equals(p.getUniqueId())){tell(p,"Tylko właściciel może nadawać zastępcę.");break;}UUID id=resolve(args);if(id==null||!a.members.remove(id)){tell(p,"Gracz musi być zwykłym członkiem.");break;}a.deputies.add(id);save();tell(p,"Awansowano "+name(id)+" na zastępcę.");}
    case "degraduj" -> {if(a==null||!a.owner.equals(p.getUniqueId())){tell(p,"Tylko właściciel może degradować zastępców.");break;}UUID id=resolve(args);if(id==null||!a.deputies.remove(id)){tell(p,"Ten gracz nie jest zastępcą.");break;}a.members.add(id);save();tell(p,"Zdegradowano zastępcę.");}
    case "wyrzuc" -> {if(a==null||!a.manager(p.getUniqueId())){tell(p,"Nie masz uprawnień.");break;}UUID id=resolve(args);if(id==null){tell(p,"Użycie: /dzialka wyrzuc <nick>");break;}if(a.deputies.contains(id)){tell(p,"Zastępcę może usunąć wyłącznie właściciel przez /dzialka degraduj.");break;}if(!a.members.remove(id)){tell(p,"Gracz nie jest członkiem.");break;}save();tell(p,"Usunięto gracza z działki.");}
    case "info" -> {Plot here=at(p.getLocation());tell(p,here==null?"Nie stoisz na działce.":"Działka: §e"+name(here.owner)+"§7, poziom: "+here.level);}
    default -> tell(p,"Komendy: /dzialka, blok, ulepsz, granice, home, ustawienia, zapros, akceptuj, zastepca, degraduj, wyrzuc, czlonkowie, info.");
  }return true;}
  private UUID resolve(String[] args){if(args.length<2)return null;Player online=Bukkit.getPlayerExact(args[1]);return online!=null?online.getUniqueId():Bukkit.getOfflinePlayer(args[1]).getUniqueId();}
  private String names(Set<UUID> ids){if(ids.isEmpty())return "brak";List<String> n=new ArrayList<>();for(UUID id:ids)n.add(name(id));return String.join(", ",n);}
  @EventHandler public void onGui(InventoryClickEvent e){String title=e.getView().getTitle();if(!title.equals(MAIN)&&!title.equals(MEMBERS)&&!title.equals(SETTINGS))return;e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p))return;int slot=e.getRawSlot();if(slot<0||slot>=e.getView().getTopInventory().getSize())return;
    if(title.equals(MAIN)){switch(slot){case 10 -> {p.closeInventory();onCommand(p,null,"dzialka",new String[]{"blok"});}case 12 -> {p.closeInventory();upgrade(p);}case 14 -> membersMenu(p);case 16 -> settingsMenu(p);case 22 -> {p.closeInventory();border(p);}}}
    else if(title.equals(MEMBERS)){if(slot==22)menu(p);else if(slot==10||slot==12||slot==14||slot==16){p.closeInventory();tell(p,slot==10?"Użyj: /dzialka zapros <nick>":slot==12?"Użyj: /dzialka zastepca <nick>":slot==14?"Użyj: /dzialka wyrzuc <nick>":"Użyj: /dzialka czlonkowie");}}
    else {if(slot==22){menu(p);return;}if(slot>=FLAGS.length)return;Plot a=own(p.getUniqueId());if(a==null||!a.owner.equals(p.getUniqueId()))return;String flag=FLAGS[slot];a.flags.put(flag,!a.flag(flag));save();settingsMenu(p);}
  }
  @EventHandler(ignoreCancelled=true) public void place(BlockPlaceEvent e){Player p=e.getPlayer();Block b=e.getBlockPlaced();if(isBlock(e.getItemInHand())){if(own(p.getUniqueId())!=null){e.setCancelled(true);tell(p,"Masz już działkę.");return;}if(b.getType()!=Material.LODESTONE){e.setCancelled(true);return;}if(getConfig().getStringList("zablokowane-swiaty").contains(b.getWorld().getName())){e.setCancelled(true);tell(p,"Na tym świecie działki są wyłączone.");return;}Plot a=new Plot(p.getUniqueId(),b.getWorld().getName(),b.getX(),b.getY(),b.getZ());for(String flag:FLAGS)a.flags.put(flag,getConfig().getBoolean("ustawienia-domyslne."+flag));if(overlaps(a,1)){e.setCancelled(true);tell(p,"Działka nachodziłaby na cudzy teren.");return;}plots.put(a.owner,a);save();Bukkit.getScheduler().runTaskLater(this,()->spawnDisplay(a),1L);tell(p,"Utworzono działkę 16×16! Otwórz /dzialka.");return;}if(!allowed(p,b.getLocation())){e.setCancelled(true);tell(p,"Nie możesz tu budować.");}}
  private boolean allowed(Player p,Location l){Plot a=at(l);return a==null||a.can(p.getUniqueId())||admin(p);}
  @EventHandler(ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){Plot a=at(e.getBlock().getLocation());if(a!=null&&isAnchor(e.getBlock(),a)){e.setCancelled(true);tell(e.getPlayer(),"Bloku Działki nie można zniszczyć.");return;}if(!allowed(e.getPlayer(),e.getBlock().getLocation()))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void interact(PlayerInteractEvent e){if(e.getClickedBlock()==null)return;Block b=e.getClickedBlock();Plot a=at(b.getLocation());
    if(e.getAction()==Action.RIGHT_CLICK_BLOCK&&a!=null&&isAnchor(b,a)){
      e.setCancelled(true);if(e.getHand()!=EquipmentSlot.HAND)return;
      if(a.can(e.getPlayer().getUniqueId())||admin(e.getPlayer()))menu(e.getPlayer());else tell(e.getPlayer(),"To Blok Działki gracza §e"+name(a.owner)+"§7.");return;
    }
if(a==null||a.can(e.getPlayer().getUniqueId())||admin(e.getPlayer()))return;Material m=b.getType();String n=m.name();boolean chest=n.contains("CHEST")||n.contains("BARREL")||n.contains("SHULKER")||n.contains("HOPPER")||n.contains("FURNACE");boolean door=n.contains("DOOR")||n.contains("FENCE_GATE")||n.contains("TRAPDOOR");if((chest&&!a.flag("skrzynie"))||(door&&!a.flag("drzwi"))||(!chest&&!door&&e.getAction()==Action.RIGHT_CLICK_BLOCK&&m.isInteractable()))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void bucketEmpty(PlayerBucketEmptyEvent e){if(!allowed(e.getPlayer(),e.getBlock().getLocation()))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void bucketFill(PlayerBucketFillEvent e){if(!allowed(e.getPlayer(),e.getBlock().getLocation()))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void damage(EntityDamageByEntityEvent e){Player victim=e.getEntity() instanceof Player p?p:null;if(victim==null)return;Player attacker=e.getDamager() instanceof Player p?p:null;if(attacker==null&&e.getDamager() instanceof Projectile proj&&proj.getShooter() instanceof Player p)attacker=p;if(attacker==null)return;Plot a=at(victim.getLocation());if(a!=null&&!a.flag("pvp"))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void spawn(CreatureSpawnEvent e){Plot a=at(e.getLocation());if(a==null)return;EntityType type=e.getEntityType();boolean hostile=e.getEntity() instanceof Monster;if(hostile&&!a.flag("potwory"))e.setCancelled(true);else if(e.getEntity() instanceof Animals&&!a.flag("zwierzeta"))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void entityExplode(EntityExplodeEvent e){e.blockList().removeIf(b->{Plot a=at(b.getLocation());return a!=null&&(isAnchor(b,a)||!a.flag("eksplozje"));});}
  @EventHandler(ignoreCancelled=true) public void blockExplode(BlockExplodeEvent e){e.blockList().removeIf(b->{Plot a=at(b.getLocation());return a!=null&&(isAnchor(b,a)||!a.flag("eksplozje"));});}
  @EventHandler(ignoreCancelled=true) public void fire(BlockSpreadEvent e){Plot a=at(e.getBlock().getLocation());if(a!=null&&!a.flag("ogien")&&e.getSource().getType()==Material.FIRE)e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void burn(BlockBurnEvent e){Plot a=at(e.getBlock().getLocation());if(a!=null&&!a.flag("ogien"))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void move(PlayerMoveEvent e){if(e.getTo()==null||e.getFrom().getBlockX()==e.getTo().getBlockX()&&e.getFrom().getBlockZ()==e.getTo().getBlockZ()&&e.getFrom().getWorld().equals(e.getTo().getWorld()))return;Player p=e.getPlayer();Plot from=at(e.getFrom()),to=at(e.getTo());if(to!=null&&!to.flag("wejscie")&&!to.can(p.getUniqueId())&&!admin(p)){e.setTo(e.getFrom());return;}UUID old=from==null?null:from.owner,now=to==null?null:to.owner;if(Objects.equals(old,now))return;if(to!=null)p.sendTitle("§aDziałka gracza","§e"+name(to.owner),5,45,10);else if(from!=null)p.sendTitle("§6Opuszczasz działkę","§e"+name(from.owner),5,45,10);
    if(to!=null&&to.flag("noc")){p.setPlayerTime(18000,false);shownNight.put(p.getUniqueId(),to.owner);}else if(shownNight.remove(p.getUniqueId())!=null)p.resetPlayerTime();
    // Latanie wymaga oddzielnego bezpiecznego systemu przyznawania; flaga jest zachowana, lecz nie daje automatycznie fly.
  }
  @EventHandler(ignoreCancelled=true) public void protectDisplay(org.bukkit.event.entity.EntityDamageEvent e){if(e.getEntity() instanceof ItemDisplay d&&d.getPersistentDataContainer().has(displayKey,PersistentDataType.STRING))e.setCancelled(true);}
  @EventHandler public void quit(PlayerQuitEvent e){UUID id=e.getPlayer().getUniqueId();BukkitTask t=borders.remove(id);if(t!=null)t.cancel();shownNight.remove(id);}
  private void load(){plots.clear();YamlConfiguration y=YamlConfiguration.loadConfiguration(file);ConfigurationSection s=y.getConfigurationSection("dzialki");if(s==null)return;for(String id:s.getKeys(false)){try{String b="dzialki."+id;UUID owner=UUID.fromString(id);Plot a=new Plot(owner,y.getString(b+".swiat"),y.getInt(b+".x"),y.getInt(b+".y"),y.getInt(b+".z"));a.level=Math.max(1,Math.min(8,y.getInt(b+".poziom",1)));if(!y.getBoolean(b+".blok-jako-srodek",false)){/* nowe granice są świadomie przeliczane wokół bloku */}for(String v:y.getStringList(b+".czlonkowie"))a.members.add(UUID.fromString(v));for(String v:y.getStringList(b+".zastepcy"))a.deputies.add(UUID.fromString(v));for(String f:FLAGS)a.flags.put(f,y.getBoolean(b+".ustawienia."+f,getConfig().getBoolean("ustawienia-domyslne."+f)));plots.put(owner,a);}catch(Exception ex){getLogger().warning("Pominięto uszkodzoną działkę "+id);}}}
  private void save(){YamlConfiguration y=new YamlConfiguration();for(Plot a:plots.values()){String b="dzialki."+a.owner;y.set(b+".swiat",a.world);y.set(b+".x",a.x);y.set(b+".y",a.y);y.set(b+".z",a.z);y.set(b+".poziom",a.level);y.set(b+".blok-jako-srodek",true);y.set(b+".czlonkowie",a.members.stream().map(UUID::toString).toList());y.set(b+".zastepcy",a.deputies.stream().map(UUID::toString).toList());for(String f:FLAGS)y.set(b+".ustawienia."+f,a.flag(f));}try{y.save(file);}catch(IOException ex){getLogger().severe("Błąd zapisu działek: "+ex.getMessage());}}
}
